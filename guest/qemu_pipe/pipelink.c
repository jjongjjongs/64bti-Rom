// qemu_pipe_link — /dev/qemu_pipe 를 virtio-serial 포트에 연결하는 최소 시험판
//
// 배경
// ----
// Android 7 의 ranchu 그래픽 스택(gralloc.ranchu, libEGL_emulation)은 호스트 렌더러와
// goldfish pipe 로 이야기한다. 프로토콜은 단순하다:
//
//     fd = open("/dev/qemu_pipe", O_RDWR);
//     write(fd, "pipe:opengles\0", 14);   // 끝의 NUL 포함
//     ... 이후로는 그냥 양방향 바이트 스트림 ...
//
// 호스트 쪽(libemugl_probe.so)은 이미 유닉스 소켓에서 이 "pipe:<서비스>" 헤더를 읽을
// 준비가 돼 있고, 앱은 그 소켓들을 virtserialport 8개로 붙여 놓았다. 즉 파이프 하나가
// virtio-serial 포트 하나에 그대로 대응된다 — 프레이밍도, 다중화 프로토콜도 필요 없다.
// 포트가 8개인 이유가 곧 "동시 파이프 8개"다.
//
// 이 프로그램의 범위
// ------------------
// 포트 하나를 골라 /dev/qemu_pipe 심볼릭 링크로 걸어주기만 한다. virtio_console 은 포트
// 하나를 동시에 한 프로세스만 열 수 있으므로(두 번째 open 은 -EBUSY), 이것으로는 파이프
// 하나밖에 못 쓴다. 제대로 하려면 open 마다 빈 포트를 배정하는 CUSE 데몬이 필요하다.
//
// 그럼에도 이걸 먼저 만드는 이유: 지금은 게스트가 파이프를 열려는 시도조차 하는지 알
// 방법이 없다. 이 링크 하나만 있으면 "libEGL_emulation 이 실제로 /dev/qemu_pipe 를 열고
// pipe:opengles 를 보내는가"가 호스트 소켓에서 눈으로 확인된다. 그 답을 보고 CUSE 를
// 만들지 결정하는 것이, 안 될 수도 있는 구조에 데몬부터 쓰는 것보다 낫다.
//
// 빌드: guest/qemu_pipe/build.sh (NDK, 정적 링크 ARM32)

#include <dirent.h>
#include <stdarg.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

// 로그는 반드시 ASCII. /dev/kmsg 는 비ASCII 를 \xNN 으로 이스케이프한다.
#define TAG "qemu_pipe_link: "
#define LINK_PATH "/dev/qemu_pipe"
#define WAIT_MS 10000
#define POLL_MS 100

static int g_log = -1;

static void put(const char *fmt, ...) __attribute__((format(printf, 1, 2)));
static void put(const char *fmt, ...) {
    if (g_log < 0) return;
    char line[384];
    int n = snprintf(line, sizeof(line), TAG);
    va_list ap;
    va_start(ap, fmt);
    n += vsnprintf(line + n, sizeof(line) - n - 2, fmt, ap);
    va_end(ap);
    if (n < 0 || (size_t)n >= sizeof(line) - 1) n = sizeof(line) - 2;
    line[n++] = '\n';
    ssize_t unused = write(g_log, line, (size_t)n);
    (void)unused;
}

// 포트 이름은 /dev/vport{컨트롤러}p{번호} 이고 컨트롤러 번호는 실행마다 달라진다
// (실측: vport1p*, vport3p* 둘 다 봤다). 이름을 고정하지 말고 가장 작은 것을 고른다.
static int pick_port(char *out, size_t len) {
    DIR *d = opendir("/dev");
    if (!d) return -1;
    char best[256] = "";
    struct dirent *e;
    while ((e = readdir(d)) != NULL) {
        if (strncmp(e->d_name, "vport", 5) != 0) continue;
        if (!best[0] || strcmp(e->d_name, best) < 0) {
            snprintf(best, sizeof(best), "%s", e->d_name);
        }
    }
    closedir(d);
    if (!best[0]) return -1;
    snprintf(out, len, "/dev/%s", best);
    return 0;
}

int main(void) {
    g_log = open("/dev/kmsg", O_WRONLY | O_CLOEXEC);

    char port[80];
    int waited = 0;
    while (pick_port(port, sizeof(port)) != 0) {
        if (waited >= WAIT_MS) {
            put("FAIL no /dev/vport* after %dms - is virtio-serial attached?", waited);
            return 1;
        }
        struct timespec ts = {0, POLL_MS * 1000000L};
        nanosleep(&ts, NULL);
        waited += POLL_MS;
    }
    put("using %s (waited %dms)", port, waited);

    // ueventd 는 vport 노드를 0600 root:root 로 만든다. 그래픽 스택은 system/graphics
    // 권한으로 도는 surfaceflinger 등에서 열리므로 그대로면 열 수 없다.
    if (chmod(port, 0666) != 0) {
        put("WARN chmod %s failed: %s", port, strerror(errno));
    }

    unlink(LINK_PATH);  // 재시작 대비. 없으면 ENOENT 인데 무시해도 된다.
    if (symlink(port, LINK_PATH) != 0) {
        put("FAIL symlink %s -> %s: %s", LINK_PATH, port, strerror(errno));
        return 1;
    }
    put("OK %s -> %s", LINK_PATH, port);
    put("NOTE only one concurrent pipe; a CUSE daemon is needed for more");
    return 0;
}
