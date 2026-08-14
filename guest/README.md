# guest/ — 게스트 쪽 구성요소

호스트(안드로이드 앱)가 아니라 **QEMU 게스트 안에서 도는** 코드입니다.
전체 절차는 [../docs/GUEST-IMAGE.md](../docs/GUEST-IMAGE.md)를 보세요.

## init_wrapper

커널 cmdline의 `rdinit=/init.wrapper`로 **PID 1**로 실행되는 정적 ARM32 바이너리.

```sh
cd init_wrapper
ANDROID_NDK_HOME=~/Android/Sdk/ndk/27.0.12077973 ./build.sh
```

NDK가 없으면 `arm-linux-gnueabihf-gcc`로 폴백합니다(검증용). 빌드 스크립트가
**정적 링크 여부를 검사**해서, 동적 링크로 나오면 실패시킵니다 — rdinit 시점에는
동적 링커가 없어 그대로 두면 부팅이 조용히 죽습니다.

### 이 버전이 하는 일

의도적으로 **최소**입니다. `docs/GUEST-IMAGE.md` 7절의 **1~5번 관문을 통과시키고,
4번(virtio-serial 존재)의 답을 로그로 남기는 것**이 목적입니다.

1. 스크래치 경로에 devtmpfs를 붙인다
2. `vport0p0`이 나타날 때까지 최대 5초 대기, 발견된 vport 노드 수를 로그
3. 스크래치를 되돌린다
4. `/init`으로 `execve` (argv/envp 보존)

기대 출력:

```
init.wrapper: start (rdinit, PID 1)
init.wrapper: OK virtio-serial ports present, waited_ms=0
init.wrapper: vport node count=8
init.wrapper: vport nodes: vport0p1 vport0p2 ... vport0p8
init.wrapper: exec /init
```

> **포트 번호는 1부터입니다.** virtio-serial 버스의 포트 0은 콘솔용으로 예약되어
> 있어서 `-device virtserialport`로 붙인 포트는 `/dev/vport0p1`부터 매겨집니다.
> 초기 버전은 `/dev/vport0p0`을 기다렸는데, 그건 애초에 생기지 않는 이름이라
> 커널이 멀쩡한데도 계속 실패로 보고했습니다.

`FAIL no /dev/vport* appeared`가 나오면 게스트 커널에
`CONFIG_VIRTIO_CONSOLE`이 빠진 것입니다. 부팅이 끝까지 가더라도 emugl 전송로가
없어 화면은 나오지 않으므로, 여기서 멈추고 커널부터 고쳐야 합니다.

### 설계상 조심한 것들

**`/dev`에 마운트하지 않습니다.** 안드로이드 first-stage init은 `/dev`에 tmpfs를
새로 마운트하고 ueventd로 노드를 만듭니다. 여기서 `/dev`를 점유하면 덮이거나
방해가 되므로, 별도 스크래치 경로에 붙여 확인만 하고 되돌립니다.

**로그 한 줄은 `write()` 한 번으로 냅니다.** `/dev/kmsg`는 write 한 번을 메시지
한 건으로 취급합니다. 태그와 본문을 나눠 쓰면 커널 로그에 조각으로 흩어져
읽기 어려워집니다.

**로그 문자열은 ASCII입니다.** `/dev/kmsg`는 비ASCII 바이트를 `\xNN`으로
이스케이프해서 내보냅니다. 한글로 쓰면 시리얼 콘솔에서 읽을 수 없습니다
(실제로 확인했습니다). 주석은 한글이지만 출력 문자열은 영문입니다.

**로그 싱크는 폴백을 거칩니다.** 커널이 `/dev/console`을 fd 0~2로 넘겨주지 못하는
경우가 있어 `kmsg` → `console` → `ttyAMA0` → stderr 순으로 시도합니다.

### 아직 안 하는 것 — 파이프 다중화

`/dev/qemu_pipe` → `/dev/vport0p*` 리다이렉션은 **일부러 넣지 않았습니다.**

goldfish pipe는 열 때마다 새 채널이 생기는데 virtio-serial 포트는 한 번에 하나만
열립니다. 따라서 분배 계층이 필요한데, **Android 7 게스트가 동시에 파이프를 몇 개나
여는지는 실제로 부팅해봐야** 알 수 있습니다. 그 값을 모르는 채로 방식을 고르면
헛수고가 되므로, 먼저 부팅을 뚫고 측정한 뒤 결정합니다.

방식 후보와 트레이드오프는 `docs/GUEST-IMAGE.md` 4.2절에 있습니다.

### 램디스크에 넣기

```sh
mkdir rd && cd rd
zcat ../ramdisk.img | cpio -idmv
cp ../init.wrapper .
chmod 750 init.wrapper
find . | cpio -o -H newc | gzip > ../ramdisk-new.img
```
