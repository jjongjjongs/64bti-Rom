// qemu_piped — /dev/qemu_pipe 를 CUSE 로 제공하고 open 마다 virtio-serial 포트를 배정한다
//
// 왜 필요한가
// ----------
// Android 7 의 ranchu 그래픽 스택은 호스트 렌더러와 goldfish pipe 로 이야기한다:
//
//     fd = open("/dev/qemu_pipe", O_RDWR);
//     write(fd, "pipe:opengles\0", 14);   // 끝의 NUL 포함, 전부 써져야 성공
//     ... 이후로는 양방향 바이트 스트림 ...
//
// 이 프로젝트에서 그 스트림은 virtio-serial 포트다. 파이프 하나가 포트 하나에 그대로
// 대응되므로 프레이밍도 다중화 프로토콜도 필요 없다 — 필요한 건 "open 마다 다른 포트"뿐.
//
// 앞선 시험판(pipelink)은 /dev/qemu_pipe 를 포트 하나에 심볼릭 링크로 걸었다. 그것으로
// 전송로가 실제로 동작한다는 것은 확인됐지만(호스트 소켓에 pipe:qemud:boot-properties 도착),
// virtio_console 은 포트당 동시 접속이 하나라서 두 번째 open 은 EBUSY 다. 실제로 그 하나를
// qemu-props 가 먼저 가져가는 바람에 SurfaceFlinger 는 파이프를 못 얻었다.
//
// 그래서 진짜 문자 장치가 필요하고, 커널 밖에서 그걸 만드는 방법이 CUSE 다. 커널이
// /dev/cuse 로 FUSE 요청을 보내면 이 데몬이 응답한다. open 요청마다 빈 포트를 배정하고,
// read/write 는 그 포트로 그대로 넘긴다.
//
// 동시성
// ------
// 디스패처(메인 스레드)는 절대 블록되면 안 된다. 한 파이프의 read 가 블록되는 동안
// 다른 파이프의 요청도 처리해야 하기 때문이다. 그래서 read/write 는 파이프별 워커
// 스레드로 넘기고, 디스패처는 큐에 넣고 곧바로 다음 요청을 읽는다. open/release/flush 는
// 블록되지 않으므로 디스패처가 직접 처리한다.
//
// 빌드: guest/qemu_pipe/build.sh (NDK, 정적 링크 ARM32)

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/sysmacros.h>
#include <time.h>
#include <unistd.h>

// ---------------------------------------------------------------------------
// FUSE/CUSE 프로토콜 정의
//
// 커널 uapi(linux/fuse.h)에서 필요한 것만 옮겨 적는다. NDK 헤더 버전에 따라 구조체가
// 없거나 다를 수 있는데, 이건 커널과 주고받는 고정된 바이너리 규약이라 직접 적는 편이
// 오히려 안전하다. 크기가 어긋나면 조용히 깨지므로 아래 컴파일 타임 검사로 못 박는다.
// ---------------------------------------------------------------------------

#define FUSE_KERNEL_VERSION 7
// 우리가 구현하는 최소 마이너. 커널은 더 높은 버전을 제시하지만, 응답에 적은 쪽으로
// 맞춰 준다. 낮게 잡을수록 구조체가 단순해서 어긋날 여지가 적다.
#define FUSE_KERNEL_MINOR_VERSION 26

enum fuse_opcode {
    FUSE_OPEN_OP = 14,
    FUSE_READ_OP = 15,
    FUSE_WRITE_OP = 16,
    FUSE_RELEASE_OP = 18,
    FUSE_FLUSH_OP = 25,
    FUSE_INIT_OP = 26,
    FUSE_INTERRUPT_OP = 36,
    FUSE_DESTROY_OP = 38,
    CUSE_INIT_OP = 4096,
};

#define FOPEN_DIRECT_IO (1 << 0)
#define FOPEN_NONSEEKABLE (1 << 2)

struct fuse_in_header {
    uint32_t len;
    uint32_t opcode;
    uint64_t unique;
    uint64_t nodeid;
    uint32_t uid;
    uint32_t gid;
    uint32_t pid;
    uint32_t padding;
};

struct fuse_out_header {
    uint32_t len;
    int32_t error;
    uint64_t unique;
};

struct cuse_init_in {
    uint32_t major;
    uint32_t minor;
    uint32_t unused;
    uint32_t flags;
};

struct cuse_init_out {
    uint32_t major;
    uint32_t minor;
    uint32_t unused;
    uint32_t flags;
    uint32_t max_read;
    uint32_t max_write;
    uint32_t dev_major;
    uint32_t dev_minor;
    uint32_t spare[10];
};

struct fuse_open_in {
    uint32_t flags;
    uint32_t unused;
};

struct fuse_open_out {
    uint64_t fh;
    uint32_t open_flags;
    uint32_t padding;
};

struct fuse_read_in {
    uint64_t fh;
    uint64_t offset;
    uint32_t size;
    uint32_t read_flags;
    uint64_t lock_owner;
    uint32_t flags;
    uint32_t padding;
};

struct fuse_write_in {
    uint64_t fh;
    uint64_t offset;
    uint32_t size;
    uint32_t write_flags;
    uint64_t lock_owner;
    uint32_t flags;
    uint32_t padding;
};

struct fuse_write_out {
    uint32_t size;
    uint32_t padding;
};

struct fuse_release_in {
    uint64_t fh;
    uint32_t flags;
    uint32_t release_flags;
    uint64_t lock_owner;
};

// 구조체 크기가 커널과 어긋나면 요청 파싱이 통째로 밀린다. 조용히 깨지느니 빌드를 막는다.
#define CHECK_SIZE(type, want) \
    typedef char check_##type[(sizeof(struct type) == (want)) ? 1 : -1]
CHECK_SIZE(fuse_in_header, 40);
CHECK_SIZE(fuse_out_header, 16);
CHECK_SIZE(cuse_init_in, 16);
CHECK_SIZE(cuse_init_out, 72);
CHECK_SIZE(fuse_open_in, 8);
CHECK_SIZE(fuse_open_out, 16);
CHECK_SIZE(fuse_read_in, 40);
CHECK_SIZE(fuse_write_in, 40);
CHECK_SIZE(fuse_write_out, 8);
CHECK_SIZE(fuse_release_in, 24);

// ---------------------------------------------------------------------------
// 설정
// ---------------------------------------------------------------------------

#define TAG "qemu_piped: "
#define CUSE_DEV "/dev/cuse"
#define PIPE_DEV "/dev/qemu_pipe"
#define DEV_NAME "qemu_pipe"
#define MAX_PIPES 32
#define MAX_WRITE (64 * 1024)
#define REQ_BUF (MAX_WRITE + 8192)

static int g_log = -1;
static int g_cuse = -1;
static pthread_mutex_t g_write_lock = PTHREAD_MUTEX_INITIALIZER;

__attribute__((format(printf, 1, 2)))
static void put(const char *fmt, ...) {
    if (g_log < 0) return;
    char line[400];
    int n = snprintf(line, sizeof(line), TAG);
    va_list ap;
    va_start(ap, fmt);
    int m = vsnprintf(line + n, sizeof(line) - (size_t)n - 2, fmt, ap);
    va_end(ap);
    if (m < 0) return;
    n += m;
    if ((size_t)n >= sizeof(line) - 1) n = (int)sizeof(line) - 2;
    line[n++] = '\n';
    ssize_t unused = write(g_log, line, (size_t)n);
    (void)unused;
}

// ---------------------------------------------------------------------------
// 포트 풀
//
// /dev/vport{컨트롤러}p{번호} 이름은 실행마다 달라진다(실측: vport1p*, vport3p* 둘 다).
// 그래서 이름을 고정하지 않고 부팅 시점에 훑어서 목록을 만든다.
// ---------------------------------------------------------------------------

struct request {
    struct request *next;
    uint64_t unique;
    uint32_t opcode;
    uint32_t size;          // read: 요청 크기, write: 페이로드 크기
    unsigned char *payload; // write 전용, malloc
};

struct pipe_slot {
    int in_use;
    int fd;                 // 배정된 vport
    int port_index;
    pthread_t worker;
    pthread_mutex_t lock;
    pthread_cond_t cv;
    struct request *head, *tail;
    int stop;
    // 바이트 수를 세는 이유: "호스트가 답을 하는가"가 지금 가장 중요한 질문인데,
    // 답하지 않는 것과 게스트가 애초에 안 보내는 것이 로그상 똑같이 조용하다.
    unsigned long tx, rx;   // tx=게스트→호스트, rx=호스트→게스트
    int rx_announced;       // 첫 응답만 한 번 알린다
    int slot_no;            // 로그에 찍을 1-기반 번호
};

static char g_ports[MAX_PIPES][288];
static int g_port_taken[MAX_PIPES];
static int g_port_count = 0;
static struct pipe_slot g_slots[MAX_PIPES];
static pthread_mutex_t g_pool_lock = PTHREAD_MUTEX_INITIALIZER;

static int scan_ports(void) {
    DIR *d = opendir("/dev");
    if (!d) return 0;
    struct dirent *e;
    while ((e = readdir(d)) != NULL && g_port_count < MAX_PIPES) {
        if (strncmp(e->d_name, "vport", 5) != 0) continue;
        snprintf(g_ports[g_port_count], sizeof(g_ports[0]), "/dev/%s", e->d_name);
        g_port_count++;
    }
    closedir(d);
    // 이름순으로 정렬해서 배정 순서를 예측 가능하게 만든다. 호스트 소켓 번호와
    // 대응이 맞아야 로그를 읽을 때 헷갈리지 않는다.
    for (int i = 0; i < g_port_count; i++) {
        for (int j = i + 1; j < g_port_count; j++) {
            if (strcmp(g_ports[j], g_ports[i]) < 0) {
                char tmp[288];
                memcpy(tmp, g_ports[i], sizeof(tmp));
                memcpy(g_ports[i], g_ports[j], sizeof(tmp));
                memcpy(g_ports[j], tmp, sizeof(tmp));
            }
        }
    }
    return g_port_count;
}

// ---------------------------------------------------------------------------
// 응답 쓰기
//
// /dev/cuse 로의 write 는 요청 하나에 정확히 한 번이어야 하고, 워커 스레드들이 동시에
// 쓸 수 있으므로 직렬화한다.
// ---------------------------------------------------------------------------

static void reply(uint64_t unique, int error, const void *data, size_t len) {
    if (error) len = 0;
    struct fuse_out_header out;
    out.len = (uint32_t)(sizeof(out) + len);
    out.error = error;
    out.unique = unique;

    unsigned char buf[REQ_BUF];
    if (sizeof(out) + len > sizeof(buf)) {
        put("reply too large (%zu), dropping", len);
        return;
    }
    memcpy(buf, &out, sizeof(out));
    if (len) memcpy(buf + sizeof(out), data, len);

    pthread_mutex_lock(&g_write_lock);
    ssize_t n = write(g_cuse, buf, sizeof(out) + len);
    pthread_mutex_unlock(&g_write_lock);
    if (n < 0 && errno != ENOENT) {
        // ENOENT 는 요청이 이미 취소된 것이라 정상이다.
        put("reply write failed: %s", strerror(errno));
    }
}

static ssize_t write_all(int fd, const unsigned char *p, size_t len) {
    size_t done = 0;
    while (done < len) {
        ssize_t n = write(fd, p + done, len - done);
        if (n < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        if (n == 0) break;
        done += (size_t)n;
    }
    return (ssize_t)done;
}

// ---------------------------------------------------------------------------
// 워커: 파이프 하나의 블로킹 I/O 를 전담한다
// ---------------------------------------------------------------------------

static void *worker_main(void *arg) {
    struct pipe_slot *s = arg;
    unsigned char buf[MAX_WRITE];

    for (;;) {
        pthread_mutex_lock(&s->lock);
        while (!s->head && !s->stop) pthread_cond_wait(&s->cv, &s->lock);
        if (s->stop && !s->head) {
            pthread_mutex_unlock(&s->lock);
            break;
        }
        struct request *r = s->head;
        s->head = r->next;
        if (!s->head) s->tail = NULL;
        pthread_mutex_unlock(&s->lock);

        if (r->opcode == FUSE_READ_OP) {
            uint32_t want = r->size > sizeof(buf) ? (uint32_t)sizeof(buf) : r->size;
            ssize_t n = read(s->fd, buf, want);
            if (n < 0) {
                reply(r->unique, -errno, NULL, 0);
            } else {
                s->rx += (unsigned long)n;
                // 호스트가 이 파이프에 처음으로 무언가 보낸 순간. 이 줄이 안 나오면
                // 게스트는 요청만 보내고 영원히 답을 기다리는 중이라는 뜻이다.
                if (n > 0 && !s->rx_announced) {
                    s->rx_announced = 1;
                    put("pipe %d: host replied (%zd bytes)", s->slot_no, n);
                }
                reply(r->unique, 0, buf, (size_t)n);
            }
        } else {  // FUSE_WRITE_OP
            ssize_t n = write_all(s->fd, r->payload, r->size);
            if (n < 0) {
                reply(r->unique, -errno, NULL, 0);
            } else {
                s->tx += (unsigned long)n;
                struct fuse_write_out wo = {.size = (uint32_t)n, .padding = 0};
                reply(r->unique, 0, &wo, sizeof(wo));
            }
        }
        free(r->payload);
        free(r);
    }
    return NULL;
}

// 큐에 넣기만 하고 즉시 돌아온다. 디스패처가 여기서 블록되면 다른 파이프가 굶는다.
static void enqueue(struct pipe_slot *s, struct request *r) {
    pthread_mutex_lock(&s->lock);
    r->next = NULL;
    if (s->tail) s->tail->next = r;
    else s->head = r;
    s->tail = r;
    pthread_cond_signal(&s->cv);
    pthread_mutex_unlock(&s->lock);
}

// ---------------------------------------------------------------------------
// 슬롯 배정/해제
// ---------------------------------------------------------------------------

static struct pipe_slot *slot_of(uint64_t fh) {
    if (fh == 0 || fh > MAX_PIPES) return NULL;
    struct pipe_slot *s = &g_slots[fh - 1];
    return s->in_use ? s : NULL;
}

static int slot_alloc(void) {
    pthread_mutex_lock(&g_pool_lock);
    int idx = -1;
    for (int i = 0; i < MAX_PIPES; i++) {
        if (!g_slots[i].in_use) { idx = i; break; }
    }
    if (idx < 0) { pthread_mutex_unlock(&g_pool_lock); return -1; }

    int port = -1;
    for (int p = 0; p < g_port_count; p++) {
        if (!g_port_taken[p]) { port = p; break; }
    }
    if (port < 0) { pthread_mutex_unlock(&g_pool_lock); return -2; }

    int fd = open(g_ports[port], O_RDWR | O_CLOEXEC);
    if (fd < 0) {
        // 포트가 다른 프로세스에 잡혀 있으면 EBUSY. 그 포트는 쓰지 않도록 표시하고
        // 다음 open 에서 다른 포트를 고르게 한다.
        put("open %s failed: %s", g_ports[port], strerror(errno));
        g_port_taken[port] = 1;
        pthread_mutex_unlock(&g_pool_lock);
        return -3;
    }

    struct pipe_slot *s = &g_slots[idx];
    memset(s, 0, sizeof(*s));
    s->in_use = 1;
    s->fd = fd;
    s->port_index = port;
    s->slot_no = idx + 1;
    pthread_mutex_init(&s->lock, NULL);
    pthread_cond_init(&s->cv, NULL);
    g_port_taken[port] = 1;

    if (pthread_create(&s->worker, NULL, worker_main, s) != 0) {
        close(fd);
        g_port_taken[port] = 0;
        s->in_use = 0;
        pthread_mutex_unlock(&g_pool_lock);
        return -4;
    }
    pthread_mutex_unlock(&g_pool_lock);
    put("pipe %d -> %s", idx + 1, g_ports[port]);
    return idx;
}

static void slot_free(struct pipe_slot *s) {
    pthread_mutex_lock(&s->lock);
    s->stop = 1;
    pthread_cond_signal(&s->cv);
    pthread_mutex_unlock(&s->lock);
    pthread_join(s->worker, NULL);

    pthread_mutex_lock(&g_pool_lock);
    close(s->fd);
    g_port_taken[s->port_index] = 0;
    put("pipe %d closed, %s freed (보냄 %lu, 받음 %lu 바이트)",
        s->slot_no, g_ports[s->port_index], s->tx, s->rx);
    s->in_use = 0;
    pthread_mutex_unlock(&g_pool_lock);
}

// ---------------------------------------------------------------------------
// CUSE 초기화
// ---------------------------------------------------------------------------

static int cuse_handshake(const struct fuse_in_header *in, const struct cuse_init_in *ci) {
    struct cuse_init_out out;
    memset(&out, 0, sizeof(out));
    out.major = FUSE_KERNEL_VERSION;
    out.minor = ci->minor < FUSE_KERNEL_MINOR_VERSION ? ci->minor : FUSE_KERNEL_MINOR_VERSION;
    out.max_read = MAX_WRITE;
    out.max_write = MAX_WRITE;
    out.dev_major = 0;  // 0 이면 커널이 동적으로 배정한다
    out.dev_minor = 0;

    // 장치 이름은 NUL 로 끝나는 "KEY=VALUE" 목록으로 뒤에 붙인다.
    static const char info[] = "DEVNAME=" DEV_NAME "\0";
    unsigned char buf[sizeof(out) + sizeof(info)];
    memcpy(buf, &out, sizeof(out));
    memcpy(buf + sizeof(out), info, sizeof(info));

    reply(in->unique, 0, buf, sizeof(buf));
    put("CUSE_INIT ok (kernel %u.%u, ours %u.%u), device /dev/%s",
        ci->major, ci->minor, out.major, out.minor, DEV_NAME);
    return 0;
}

// 경로에 무엇이 있는지 사람이 읽을 수 있게 적는다. 진단에만 쓴다.
static void describe_path(const char *path, char *out, size_t len) {
    struct stat st;
    if (lstat(path, &st) != 0) {
        snprintf(out, len, "없음 (%s)", strerror(errno));
        return;
    }
    if (S_ISLNK(st.st_mode)) {
        char target[256];
        ssize_t n = readlink(path, target, sizeof(target) - 1);
        if (n < 0) { snprintf(out, len, "심볼릭 링크 (readlink 실패)"); return; }
        target[n] = '\0';
        // 링크가 가리키는 곳이 실제로 있는지가 핵심이다. 없으면 stat 은 실패하는데
        // mknod 는 EEXIST 를 내서, 노드가 있는 것처럼도 없는 것처럼도 보인다.
        snprintf(out, len, "심볼릭 링크 -> %s (%s)",
                 target, access(path, F_OK) == 0 ? "대상 있음" : "대상 없음");
        return;
    }
    if (S_ISCHR(st.st_mode)) {
        snprintf(out, len, "문자 장치 %u:%u 권한 %04o",
                 major(st.st_rdev), minor(st.st_rdev), st.st_mode & 07777);
        return;
    }
    snprintf(out, len, "다른 무엇 (모드 %o)", st.st_mode);
}

// 장치 노드는 우리가 직접 만든다.
//
// 커널은 CUSE_INIT 응답을 받으면 /sys/class/cuse/<이름> 아래에 장치를 등록하고 uevent 를
// 쏜다. 원래는 ueventd 가 그걸 보고 /dev 에 노드를 만들어야 하는데, 실측으로 만들어지지
// 않았다(CUSE_INIT 은 성공했는데 /dev/qemu_pipe 가 끝내 안 생김). ueventd 의 규칙을
// 파고드는 대신, 커널이 배정한 major:minor 를 sysfs 에서 읽어 우리가 mknod 한다.
// 어차피 권한도 0666 으로 열어줘야 한다 — ueventd 기본값은 0600 root:root 인데
// surfaceflinger 는 system 권한으로 돈다.
//
// 만드는 방식이 mknod 직접 호출이 아니라 "임시 이름으로 만들고 rename" 인 이유:
//
// 실측에서 stat 은 "없다"고 하는데 같은 자리에 한 mknod 가 EEXIST 로 실패했다. 둘 다
// 참일 수 있는 경우는 하나뿐이다 — stat 은 링크를 따라가고 mknod 는 따라가지 않으므로,
// 대상이 없는 심볼릭 링크가 그 자리에 있었던 것이다. 그리고 그 뒤로 access(F_OK) 가
// 계속 실패해서 pipetest 도 SurfaceFlinger 도 장치가 없다고 봤다.
//
// 누가 그 링크를 만들었는지 캐는 것보다, 무엇이 있든 원자적으로 밀어내는 편이 낫다.
// rename 은 심볼릭 링크든 낡은 노드든 대상을 조용히 대체하고, ueventd 가 같은 순간에
// 노드를 만들어도 경합이 생기지 않는다.
static int create_pipe_node(unsigned major_no, unsigned minor_no) {
    char before[320];
    describe_path(PIPE_DEV, before, sizeof(before));

    const char *tmp = "/dev/.qemu_pipe.new";
    unlink(tmp);
    if (mknod(tmp, S_IFCHR | 0666, makedev(major_no, minor_no)) != 0) {
        put("FAIL mknod %s (%u:%u): %s", tmp, major_no, minor_no, strerror(errno));
        return -1;
    }
    // mknod 는 umask 를 먹으므로 권한을 다시 못박는다. surfaceflinger 는 root 가 아니다.
    if (chmod(tmp, 0666) != 0) put("WARN chmod %s: %s", tmp, strerror(errno));
    if (rename(tmp, PIPE_DEV) != 0) {
        put("FAIL rename %s -> %s: %s", tmp, PIPE_DEV, strerror(errno));
        unlink(tmp);
        return -1;
    }

    char after[320];
    describe_path(PIPE_DEV, after, sizeof(after));
    put("created %s as %u:%u (이전: %s / 이후: %s)",
        PIPE_DEV, major_no, minor_no, before, after);
    return 0;
}

static void *device_watcher(void *unused) {
    (void)unused;
    const char *devattr = "/sys/class/cuse/" DEV_NAME "/dev";
    unsigned major_no = 0, minor_no = 0;
    int have = 0;

    for (int i = 0; i < 600 && !have; i++) {
        int fd = open(devattr, O_RDONLY | O_CLOEXEC);
        if (fd < 0) {
            struct timespec ts = {0, 100 * 1000000L};
            nanosleep(&ts, NULL);
            continue;
        }
        char buf[64] = {0};
        ssize_t n = read(fd, buf, sizeof(buf) - 1);
        close(fd);
        if (n > 0 && sscanf(buf, "%u:%u", &major_no, &minor_no) == 2) {
            have = 1;
            break;
        }
        put("WARN %s unreadable: %s", devattr, strerror(errno));
        return NULL;
    }
    if (!have) {
        // 커널이 장치를 아예 등록하지 않은 것이다 — CUSE_INIT 응답 자체를 커널이
        // 거부했다는 뜻이라, ueventd 가 아니라 우리 응답을 봐야 한다.
        put("FAIL %s never appeared - kernel did not register the CUSE device", devattr);
        return NULL;
    }

    if (create_pipe_node(major_no, minor_no) != 0) return NULL;

    // 여기서 우리가 직접 open 해보지는 않는다. 커널은 CUSE_INIT 응답을 처리하는 도중에
    // 이미 sysfs 에 장치를 올리므로, 이 스레드가 그 틈에 열면 ENXIO 가 난다(실측:
    // "FAIL open /dev/qemu_pipe: No such device or address" — 노드는 멀쩡한데 아직
    // 살아나기 전이었다). 게다가 자기 자신을 여는 것은 디스패처에게 FUSE_OPEN 을
    // 보내는 일이라 굳이 할 이유가 없다. 열리는지는 pipetest 가 확인한다.

    // 만들어 놓고 끝내지 않는다. 지금까지 못 본 실패 방식이 하나 남아 있다 — 노드가
    // 생겼다가 나중에 사라지는 경우(ueventd 의 remove 처리 등). 한동안 지켜보다가
    // 없어지면 다시 만들고, 그 사실을 로그에 남긴다.
    for (int i = 0; i < 600; i++) {
        struct timespec ts = {0, 500 * 1000000L};
        nanosleep(&ts, NULL);
        if (access(PIPE_DEV, F_OK) == 0) continue;
        char gone[320];
        describe_path(PIPE_DEV, gone, sizeof(gone));
        put("WARN %s 가 %dms 만에 사라졌다 (%s) - 다시 만든다",
            PIPE_DEV, (i + 1) * 500, gone);
        if (create_pipe_node(major_no, minor_no) != 0) return NULL;
    }
    return NULL;
}

// ---------------------------------------------------------------------------

int main(void) {
    g_log = open("/dev/kmsg", O_WRONLY | O_CLOEXEC);

    // virtio-serial 포트가 나타날 때까지 기다린다. ueventd 콜드부트 직후라 보통 이미 있다.
    for (int i = 0; i < 100 && scan_ports() == 0; i++) {
        struct timespec ts = {0, 100 * 1000000L};
        nanosleep(&ts, NULL);
    }
    if (g_port_count == 0) {
        put("FAIL no /dev/vport* found - is virtio-serial attached?");
        return 1;
    }
    put("found %d virtio-serial ports, first=%s", g_port_count, g_ports[0]);

    g_cuse = open(CUSE_DEV, O_RDWR | O_CLOEXEC);
    if (g_cuse < 0) {
        put("FAIL open %s: %s (need CONFIG_CUSE=y)", CUSE_DEV, strerror(errno));
        return 1;
    }

    pthread_t watcher;
    pthread_create(&watcher, NULL, device_watcher, NULL);
    pthread_detach(watcher);

    static unsigned char buf[REQ_BUF];
    for (;;) {
        ssize_t n = read(g_cuse, buf, sizeof(buf));
        if (n < 0) {
            if (errno == EINTR || errno == EAGAIN) continue;
            if (errno == ENODEV) { put("device gone, exiting"); break; }
            put("read %s failed: %s", CUSE_DEV, strerror(errno));
            break;
        }
        if ((size_t)n < sizeof(struct fuse_in_header)) continue;

        struct fuse_in_header in;
        memcpy(&in, buf, sizeof(in));
        const unsigned char *body = buf + sizeof(in);
        size_t body_len = (size_t)n - sizeof(in);

        switch (in.opcode) {
        case CUSE_INIT_OP: {
            if (body_len < sizeof(struct cuse_init_in)) { reply(in.unique, -EINVAL, NULL, 0); break; }
            struct cuse_init_in ci;
            memcpy(&ci, body, sizeof(ci));
            cuse_handshake(&in, &ci);
            break;
        }
        case FUSE_OPEN_OP: {
            int idx = slot_alloc();
            if (idx < 0) {
                put("open refused (%d): no free pipe (%d ports, all busy)", idx, g_port_count);
                reply(in.unique, -ENOSPC, NULL, 0);
                break;
            }
            struct fuse_open_out oo;
            memset(&oo, 0, sizeof(oo));
            oo.fh = (uint64_t)(idx + 1);
            // 스트림 장치다. 페이지 캐시를 끼우면 안 되고 seek 도 의미가 없다.
            oo.open_flags = FOPEN_DIRECT_IO | FOPEN_NONSEEKABLE;
            reply(in.unique, 0, &oo, sizeof(oo));
            break;
        }
        case FUSE_READ_OP: {
            if (body_len < sizeof(struct fuse_read_in)) { reply(in.unique, -EINVAL, NULL, 0); break; }
            struct fuse_read_in ri;
            memcpy(&ri, body, sizeof(ri));
            struct pipe_slot *s = slot_of(ri.fh);
            if (!s) { reply(in.unique, -EBADF, NULL, 0); break; }
            struct request *r = calloc(1, sizeof(*r));
            if (!r) { reply(in.unique, -ENOMEM, NULL, 0); break; }
            r->unique = in.unique;
            r->opcode = FUSE_READ_OP;
            r->size = ri.size;
            enqueue(s, r);
            break;
        }
        case FUSE_WRITE_OP: {
            if (body_len < sizeof(struct fuse_write_in)) { reply(in.unique, -EINVAL, NULL, 0); break; }
            struct fuse_write_in wi;
            memcpy(&wi, body, sizeof(wi));
            struct pipe_slot *s = slot_of(wi.fh);
            if (!s) { reply(in.unique, -EBADF, NULL, 0); break; }
            size_t avail = body_len - sizeof(wi);
            size_t len = wi.size < avail ? wi.size : avail;
            struct request *r = calloc(1, sizeof(*r));
            if (!r) { reply(in.unique, -ENOMEM, NULL, 0); break; }
            r->payload = malloc(len ? len : 1);
            if (!r->payload) { free(r); reply(in.unique, -ENOMEM, NULL, 0); break; }
            memcpy(r->payload, body + sizeof(wi), len);
            r->unique = in.unique;
            r->opcode = FUSE_WRITE_OP;
            r->size = (uint32_t)len;
            enqueue(s, r);
            break;
        }
        case FUSE_RELEASE_OP: {
            if (body_len < sizeof(struct fuse_release_in)) { reply(in.unique, -EINVAL, NULL, 0); break; }
            struct fuse_release_in rl;
            memcpy(&rl, body, sizeof(rl));
            struct pipe_slot *s = slot_of(rl.fh);
            if (s) slot_free(s);
            reply(in.unique, 0, NULL, 0);
            break;
        }
        case FUSE_FLUSH_OP:
            reply(in.unique, 0, NULL, 0);
            break;
        case FUSE_INTERRUPT_OP:
            // 응답하지 않는 요청이다.
            break;
        case FUSE_DESTROY_OP:
            reply(in.unique, 0, NULL, 0);
            break;
        default:
            // poll/ioctl 등은 구현하지 않는다. Android 7 의 qemu_pipe 클라이언트는
            // open/write/read/close 만 쓴다.
            reply(in.unique, -ENOSYS, NULL, 0);
            break;
        }
    }
    return 0;
}
