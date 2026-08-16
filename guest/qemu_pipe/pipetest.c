// pipetest — /dev/qemu_pipe 를 여러 개 동시에 열어 다중화가 실제로 되는지 확인한다
//
// CI 에는 호스트 렌더러가 없으므로 GLES 가 도는지는 확인할 수 없다. 하지만 확인해야 할
// 것이자 확인 가능한 것은 따로 있다 — CUSE 데몬이 open 마다 서로 다른 virtio-serial
// 포트를 배정하는가. 그게 이 데몬의 존재 이유 전부다.
//
// 파이프를 세 개 열어 서로 다른 서비스 이름을 쓴다. 호스트 청취기에 서로 다른 소켓으로
// 각각의 이름이 나타나면 다중화가 동작하는 것이고, 하나만 나타나거나 두 번째 open 이
// 실패하면 안 되는 것이다. 심볼릭 링크 방식에서는 반드시 후자가 된다.

#include <errno.h>
#include <fcntl.h>
#include <stdarg.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#define TAG "pipetest: "
#define PIPE_DEV "/dev/qemu_pipe"
#define N_PIPES 3

static int g_log = -1;

__attribute__((format(printf, 1, 2)))
static void put(const char *fmt, ...) {
    if (g_log < 0) return;
    char line[320];
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

// 이 시험은 CI 에서만 돈다.
//
// 파이프 세 개를 5초 동안 쥐는데, 하필 그 5초가 부팅에서 가장 붐비는 구간이다. 포트는
// 여덟 개뿐이고 실제 사용자는 boot-properties, adb, SurfaceFlinger, bootanimation,
// system_server 로 이미 다섯이다 — 시험까지 합치면 정확히 여덟, 여유가 0 이다.
//
// 느린 첫 부팅(dexopt 가 도는)에서는 이것들이 시간축에 흩어져서 문제가 없었다. 그런데
// /data 가 채워진 뒤의 두 번째 부팅은 system_server 가 13초에 뜬다(실측: 첫 부팅 56초).
// 전부 좁은 구간에 몰리므로 경합이 실제로 일어날 수 있다.
//
// 그래서 커널 커맨드라인에 표시가 있을 때만 돈다. CI 는 그 표시를 붙이고 앱은 붙이지
// 않으므로, 검증은 그대로 하면서 폰에서는 포트를 한 개도 쓰지 않는다.
#define ENABLE_FLAG "singlevm.pipetest=1"

static int enabled(void) {
    int fd = open("/proc/cmdline", O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        // 못 읽으면 돌지 않는다. 포트를 괜히 빼앗는 쪽보다 시험을 건너뛰는 쪽이 낫다.
        put("SKIP /proc/cmdline 을 읽을 수 없다 (%s)", strerror(errno));
        return 0;
    }
    char cmdline[2048];
    ssize_t n = read(fd, cmdline, sizeof(cmdline) - 1);
    close(fd);
    if (n <= 0) { put("SKIP /proc/cmdline 이 비어 있다"); return 0; }
    cmdline[n] = '\0';
    if (!strstr(cmdline, ENABLE_FLAG)) {
        put("SKIP %s 가 없다 - 포트를 쓰지 않고 끝낸다", ENABLE_FLAG);
        return 0;
    }
    return 1;
}

int main(void) {
    g_log = open("/dev/kmsg", O_WRONLY | O_CLOEXEC);
    if (!enabled()) return 0;

    // 실제 클라이언트가 쓰는 것과 같은 이름들. 호스트 로그에서 어느 파이프인지 구분된다.
    static const char *services[N_PIPES] = {
        "pipe:pipetest:one",
        "pipe:pipetest:two",
        "pipe:pipetest:three",
    };
    // 데몬보다 먼저 도는 경우가 있다(실측: 0/3). 장치가 생길 때까지 기다린다.
    int waited = 0;
    while (access(PIPE_DEV, F_OK) != 0) {
        if (waited >= 60000) {
            // access 는 심볼릭 링크를 따라간다. 대상 없는 링크가 자리를 차지하고 있으면
            // "없다"고 나오는데 정작 그 자리는 비어 있지 않다(실측). lstat 으로 구분한다.
            struct stat st;
            if (lstat(PIPE_DEV, &st) == 0) {
                char target[256];
                ssize_t n = readlink(PIPE_DEV, target, sizeof(target) - 1);
                if (n >= 0) {
                    target[n] = '\0';
                    put("FAIL %s 는 대상 없는 심볼릭 링크다 -> %s", PIPE_DEV, target);
                } else {
                    put("FAIL %s 가 있는데 열리지 않는다 (모드 %o)", PIPE_DEV, st.st_mode);
                }
            } else {
                put("FAIL %s never appeared after %dms", PIPE_DEV, waited);
            }
            return 1;
        }
        usleep(200 * 1000);
        waited += 200;
    }
    put("%s ready after %dms", PIPE_DEV, waited);

    int fds[N_PIPES];
    int opened = 0;

    for (int i = 0; i < N_PIPES; i++) {
        fds[i] = open(PIPE_DEV, O_RDWR);
        if (fds[i] < 0) {
            put("FAIL open #%d failed: %s", i + 1, strerror(errno));
            continue;
        }
        opened++;
        size_t len = strlen(services[i]) + 1;  // 끝의 NUL 까지 보내야 한다
        ssize_t n = write(fds[i], services[i], len);
        if (n != (ssize_t)len) {
            put("FAIL write #%d: %zd of %zu (%s)", i + 1, n, len, strerror(errno));
        } else {
            put("OK pipe #%d open+write %s", i + 1, services[i]);
        }
    }

    put("%s %d/%d pipes opened concurrently",
        opened == N_PIPES ? "RESULT ok:" : "RESULT PARTIAL:", opened, N_PIPES);

    // 호스트가 읽어갈 시간을 잠깐 준 뒤 닫는다. 닫으면 포트가 풀로 돌아가므로,
    // 이 프로그램이 끝난 뒤에는 그래픽 스택이 세 개를 다시 쓸 수 있어야 한다.
    sleep(5);
    for (int i = 0; i < N_PIPES; i++) {
        if (fds[i] >= 0) close(fds[i]);
    }
    put("closed all, ports returned to the pool");
    return opened == N_PIPES ? 0 : 1;
}
