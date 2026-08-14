// init.wrapper — 게스트 부팅 경로 1차 관문 진단용 rdinit
//
// 커널 cmdline의 rdinit=/init.wrapper 로 PID 1로 실행된 뒤, virtio-serial 포트가
// 실제로 존재하는지 확인해서 시리얼 콘솔에 남기고, 원래 /init 으로 넘긴다.
//
// 이 버전은 의도적으로 "최소"다. goldfish pipe 다중화(/dev/qemu_pipe -> vportN)는
// 아직 하지 않는다. Android 게스트가 파이프를 동시에 몇 개나 여는지는 실제로
// 부팅해봐야 알 수 있고, 그 값이 정해지기 전에 다중화 방식을 고르면 헛수고가 되기
// 때문이다. 지금 목표는 docs/GUEST-IMAGE.md 7절의 1~5번 관문을 통과시키는 것.
//
// 빌드: ./build.sh  (NDK 필요, 정적 링크 ARM32)

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mount.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

// 로그 문자열은 반드시 ASCII 로 유지할 것. /dev/kmsg 는 비ASCII 바이트를
// \xNN 형태로 이스케이프해서 내보내므로, 한글을 쓰면 시리얼 콘솔에서 읽을 수 없다.
#define TAG "init.wrapper: "
#define SCRATCH_DEV "/init_wrapper_dev"
#define VPORT_WAIT_MS 5000
#define VPORT_POLL_MS 50

static int g_log = -1;

// 한 줄을 반드시 write() 한 번으로 내보낸다. /dev/kmsg 는 write 한 번을 메시지
// 한 건으로 취급하므로, 태그와 본문을 나눠 쓰면 커널 로그에 조각으로 흩어진다.
static void put_line(const char *a, const char *b) {
    if (g_log < 0) return;
    char line[512];
    int n = snprintf(line, sizeof(line), TAG "%s%s\n", a ? a : "", b ? b : "");
    if (n < 0) return;
    size_t len = (size_t)n < sizeof(line) ? (size_t)n : sizeof(line) - 1;
    ssize_t unused = write(g_log, line, len);
    (void)unused;
}

static void put_num(const char *a, long v) {
    char buf[32];
    snprintf(buf, sizeof(buf), "%ld", v);
    put_line(a, buf);
}

// 로그를 어디로 낼지는 부팅 시점에 따라 달라진다. 커널이 /dev/console 을 fd 0..2 로
// 열어주지 못했을 수도 있으므로, 먹히는 첫 번째 싱크를 쓴다. /dev/kmsg 가 가장
// 확실하다 — 커널 링버퍼로 들어가 console=ttyAMA0 으로 그대로 나온다.
static void log_open(const char *dev_root) {
    static char path[256];
    const char *names[] = {"/kmsg", "/console", "/ttyAMA0"};
    for (size_t i = 0; i < sizeof(names) / sizeof(names[0]); i++) {
        snprintf(path, sizeof(path), "%s%s", dev_root, names[i]);
        int fd = open(path, O_WRONLY | O_CLOEXEC);
        if (fd >= 0) { g_log = fd; return; }
    }
    if (g_log < 0) g_log = 2;  // 커널이 넘겨준 stderr 에 기대본다
}

/**
 * Counts virtio-serial port nodes and records the first few names.
 *
 * Deliberately matches any vport* rather than a fixed name: port 0 of a virtio-serial bus is
 * reserved for the console, so ports added with -device virtserialport are numbered from
 * /dev/vport0p1 upwards. Waiting on /dev/vport0p0 waits for something that is never created.
 */
static int count_vports(char *names, size_t names_len) {
    DIR *d = opendir(SCRATCH_DEV);
    if (!d) return -1;
    int n = 0;
    size_t used = 0;
    if (names && names_len) names[0] = '\0';
    struct dirent *e;
    while ((e = readdir(d)) != NULL) {
        if (strncmp(e->d_name, "vport", 5) != 0) continue;
        n++;
        if (names && used + strlen(e->d_name) + 2 < names_len) {
            if (used) { names[used++] = ' '; names[used] = '\0'; }
            strcpy(names + used, e->d_name);
            used += strlen(e->d_name);
        }
    }
    closedir(d);
    return n;
}

static int wait_for_vport(int timeout_ms) {
    int waited = 0;
    while (waited <= timeout_ms) {
        if (count_vports(NULL, 0) > 0) return waited;
        struct timespec ts = {0, VPORT_POLL_MS * 1000000L};
        nanosleep(&ts, NULL);
        waited += VPORT_POLL_MS;
    }
    return -1;
}

// devtmpfs 를 /dev 가 아니라 스크래치 경로에 붙인다. 안드로이드 first-stage init 은
// /dev 에 tmpfs 를 새로 마운트하고 ueventd 로 노드를 만들기 때문에, 여기서 /dev 를
// 점유하면 덮이거나 방해가 된다. 확인만 하고 원상복구한다.
static int scratch_mount(void) {
    if (mkdir(SCRATCH_DEV, 0755) != 0 && errno != EEXIST) return -1;
    if (mount("devtmpfs", SCRATCH_DEV, "devtmpfs", MS_NOSUID, NULL) != 0) return -1;
    return 0;
}

static void scratch_unmount(void) {
    umount(SCRATCH_DEV);
    rmdir(SCRATCH_DEV);
}

int main(int argc, char **argv, char **envp) {
    (void)argc;

    int mounted = scratch_mount() == 0;
    log_open(mounted ? SCRATCH_DEV : "/dev");

    put_line("start (rdinit, PID 1)", NULL);
    if (!mounted) {
        put_line("WARN devtmpfs mount failed (check CONFIG_DEVTMPFS): ", strerror(errno));
    } else {
        // 4번 관문: virtio-serial 포트가 실제로 보이는가.
        // 없으면 emugl 전송로가 없다는 뜻이고, 부팅이 끝까지 가더라도 화면은 안 나온다.
        char names[256];
        int waited = wait_for_vport(VPORT_WAIT_MS);
        int found = count_vports(names, sizeof(names));
        if (waited < 0) {
            put_line("FAIL no /dev/vport* appeared", NULL);
            put_line("  -> check CONFIG_VIRTIO_CONSOLE=y in the guest kernel", NULL);
            put_line("  -> host attaches 8 ports via virtio-serial-device (mmio)", NULL);
        } else {
            put_num("OK virtio-serial ports present, waited_ms=", waited);
        }
        // 성공이든 실패든 실제 상황을 보고한다. 실패 경로에서 개수를 안 찍는 바람에
        // "몇 개가 있긴 한가"조차 알 수 없었다.
        put_num("vport node count=", found);
        put_line("vport nodes: ", found > 0 ? names : "(없음)");
        scratch_unmount();
    }

    // 원래 init 으로 넘긴다. argv/envp 를 그대로 보존해야 안드로이드 init 이
    // 자기가 first stage 인지 판단하는 로직이 깨지지 않는다.
    static const char *candidates[] = {"/init", "/sbin/init", "/system/bin/init"};
    for (size_t i = 0; i < sizeof(candidates) / sizeof(candidates[0]); i++) {
        if (access(candidates[i], X_OK) != 0) continue;
        put_line("exec ", candidates[i]);
        argv[0] = (char *)candidates[i];
        execve(candidates[i], argv, envp);
        put_line("exec failed: ", strerror(errno));
    }

    // 여기 도달했다면 넘길 init 이 없다. PID 1 이 죽으면 커널이 패닉을 내는데,
    // 위 로그가 그 직전에 이유를 남긴다.
    put_line("FATAL no executable /init found - check ramdisk layout", NULL);
    return 1;
}
