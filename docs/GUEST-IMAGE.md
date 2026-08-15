# 게스트 이미지 만들기

호스트(앱) 쪽은 대체로 서 있습니다. 지금 비어 있는 건 **게스트 이미지**이고, 이
문서는 그걸 만드는 절차입니다.

배경과 판단 근거는 [ARCHITECTURE-NOTES.md](ARCHITECTURE-NOTES.md)에 있습니다.
요약하면 — QEMU는 손댈 필요 없고, 게스트가 표준 **ranchu HAL**을 로드한 뒤
`/dev/qemu_pipe`를 열면 `/init.wrapper`가 그걸 virtio-serial로 돌려주고, 호스트의
`libemugl_probe.so`가 받아서 렌더링하는 구조입니다.

---

> **현황 (이 문서에서 가장 먼저 볼 곳): 7절.**
> 게스트는 부팅해서 `/system` `/cache` `/data` 를 마운트하고 zygote 와 system_server 까지
> 올라옵니다. 지금 막혀 있는 곳은 SurfaceFlinger 하나이고, 원인은 goldfish pipe 전송로
> 부재로 보입니다(7.3). CI 가 매 커밋마다 이미지를 조립하고 스모크 부팅까지 돌립니다.

## 0. 대원칙: 폰이 아니라 PC에서 먼저 잡는다

> ⚠ **이 절은 더 이상 유효하지 않습니다.** PC 를 쓸 수 없는 상황이라, 여기 적힌 작업은
> 전부 `.github/workflows/guest-image.yml` 이 GitHub 러너에서 대신합니다. 커널 빌드는
> 캐시되고(스크립트가 안 바뀌면 재사용), 조립·스모크 부팅·판정까지 한 번에 돕니다.
> 결과 아티팩트를 폰에서 내려받아 앱의 "Android 7 구성 가져오기" 로 넣으면 됩니다.


**이게 이 문서에서 제일 중요한 항목입니다.**

게스트 이미지를 폰에 넣고 앱으로 돌리면 한 번 시도에 수 분이 걸리고, 실패해도
원인이 잘 안 보입니다. 반면 **PC의 데스크톱 QEMU에서 똑같은 명령줄을 그대로
재현할 수 있습니다.** 앱이 만드는 인자는 표준 업스트림 QEMU 인자라 그대로 돕니다
(실제로 QEMU 8.2.2에서 검증했습니다).

```sh
sudo apt install qemu-system-arm     # qemu-system-aarch64 가 함께 설치됨
```

부팅 로그가 터미널에 바로 흐르고, 커널 패닉이든 init 실패든 즉시 보입니다.
**게스트가 PC에서 부팅될 때까지는 폰에 올리지 마세요.** 6절에 그대로 쓸 수 있는
명령줄이 있습니다.

---

## 1. 필요한 세 조각

| 조각 | 어떻게 얻나 | 난이도 |
|---|---|---|
| `system.img` | **Android SDK 기성품 그대로** | 쉬움 |
| `kernel` | 빌드 또는 SDK 것 시도 | 중간 |
| `ramdisk.img` + `/init.wrapper` | 기성 램디스크에 바이너리 추가 | 어려움 |

가장 큰 덩어리인 `system.img`가 공짜로 해결된다는 게 핵심입니다.

---

## 2. system.img — SDK 기성품 사용

Android 7.0(API 24) ARM32 에뮬레이터 이미지가 **정확히 우리가 필요한 것**입니다.
ranchu HAL(`gralloc.ranchu`, `libEGL_emulation`)이 이미 들어 있습니다.

```sh
sdkmanager "system-images;android-24;default;armeabi-v7a"
```

받으면 여기 있습니다:

```
$ANDROID_HOME/system-images/android-24/default/armeabi-v7a/
├── system.img
├── ramdisk.img
├── userdata.img
├── kernel-ranchu        (또는 kernel-qemu)
└── build.prop
```

**포맷을 반드시 확인하세요.** 앱은 `format=raw`로 붙입니다:

```sh
file system.img
```

- `Linux rev 1.0 ext4 filesystem data` → 그대로 사용 가능
- `Android sparse image` → 변환 필요:
  ```sh
  simg2img system.img system.raw.img && mv system.raw.img system.img
  ```
  (`simg2img`는 `android-sdk-libsparse-utils` 패키지)

`userdata.img`는 SDK 것이 작습니다. 게임 설치 공간이 필요하면 키우세요:

```sh
truncate -s 4G userdata.img
```

---

## 3. 커널

### 3.1 반드시 켜져 있어야 하는 config

```
CONFIG_VIRTIO=y
CONFIG_VIRTIO_MMIO=y            # virtio-blk-device (디스크)
CONFIG_VIRTIO_BLK=y
CONFIG_VIRTIO_CONSOLE=y         # virtio-serial = emugl 전송로
# PCI는 필요 없습니다 (3.2 참고 — 호스트를 MMIO로 통일했습니다)
CONFIG_ANDROID_BINDER_IPC=y     # cmdline의 binder.devices=
CONFIG_ANDROID_BINDER_DEVICES="binder,hwbinder,vndbinder"
CONFIG_SERIAL_AMBA_PL011=y      # cmdline의 console=ttyAMA0
CONFIG_SERIAL_AMBA_PL011_CONSOLE=y
CONFIG_BLK_DEV_INITRD=y         # rdinit=
```

### 3.2 virtio 버스: MMIO로 통일했습니다

이전 판에서는 호스트가 버스를 섞어 쓰고 있었습니다 — 디스크는 virtio-mmio
(`virtio-blk-device`)인데 emugl 전송로와 GPU는 PCI(`virtio-serial-pci`,
`virtio-gpu-pci`)였습니다.

goldfish/ranchu 계열 ARM 커널은 보통 **virtio-mmio 전용**으로 빌드되어 PCIe 호스트
브리지가 꺼져 있습니다. 그 상태에서는 디스크는 붙지만 **virtio-serial이 보이지
않아** `/dev/vport0p*`가 생기지 않고, 그러면 부팅에 성공하더라도 emugl 전송로가
없어 화면이 영원히 나오지 않습니다.

**그래서 호스트를 전부 MMIO로 통일했습니다.** 이제 커널에 PCI가 필요 없습니다.

| 디바이스 | 변경 전 | 변경 후 |
|---|---|---|
| 디스크 | `virtio-blk-device` | 그대로 (원래 MMIO) |
| emugl 파이프 | `virtio-serial-pci,disable-legacy=on` | **`virtio-serial-device`** |
| GPU | `virtio-gpu-pci,xres=,yres=` | **`virtio-gpu-device,xres=,yres=`** |

> `disable-legacy`는 **virtio-pci 전용 속성**이라 그대로 옮기면 QEMU가 기동조차
> 하지 못합니다 (`Property 'virtio-serial-device.disable-legacy' not found`).
> 그래서 제거했습니다. virt 머신의 virtio-mmio는 기본이 virtio 1.0이라 의미상
> 잃는 것도 없습니다. `xres`/`yres`는 MMIO에서도 그대로 동작합니다.
>
> 위 조합은 QEMU 8.2.2에서 실제로 검증했습니다 — 정상 기동하고 포트 8개가 모두
> MMIO virtio-serial 컨트롤러에 붙습니다.

여전히 **부팅 후 `/dev/vport0p0` 존재 여부가 최대 관문**입니다(7절 4번). 다만 이제
원인이 PCI 부재가 아니라 `CONFIG_VIRTIO_CONSOLE` 누락 쪽입니다.

### 3.2b ⚠ 콘솔이 조용한 문제 (실측)

첫 실기 실행에서 **QEMU가 11초 동안 시리얼 출력을 한 줄도 내지 않고 종료 코드 0으로
끝났습니다.** 커널 패닉이면 뭐라도 찍혔을 텐데 완전 무음이었습니다.

원인으로 가장 유력한 것은 **콘솔 불일치**입니다. ranchu 보드는 콘솔이
`goldfish_tty`(`ttyGF0`)인데 우리는 `-machine virt`의 PL011(`ttyAMA0`)을 씁니다.
SDK의 `kernel-ranchu`에 `CONFIG_SERIAL_AMBA_PL011`이 없으면 커널은 잘 돌면서도
**출력할 곳이 없어 무음으로 부팅**합니다.

그래서 기본 cmdline에 **earlycon**을 넣었습니다:

```
console=ttyAMA0 earlycon=pl011,0x09000000 keep_bootcon ignore_loglevel ...
```

`earlycon`은 드라이버 probe 전에 virt 머신의 UART(`0x09000000`)에 직접 씁니다.
`keep_bootcon`은 나중에 콘솔이 넘어가면서 조용해지는 걸 막습니다.

**이걸로도 무음이면 커널이 아예 시작하지 못한 것**이고, 커널을 교체해야 합니다.

### 3.2c cmdline 재정의 파일 — 앱 재빌드 없이 바꾸기

cmdline을 한 글자 바꿀 때마다 CI 재빌드 → 다운로드 → 재설치는 너무 느립니다.
그래서 앱이 이 파일을 읽습니다:

```
Android/data/com.example.singlevm/files/import/kernel_cmdline.txt
```

파일 관리자로 접근되는 위치입니다. 내용이 있으면 **그 한 줄이 `-append` 전체를
대체**합니다(줄바꿈은 공백으로 치환하니 편집기가 줄을 접어도 됩니다). 실행 로그에
어떤 cmdline을 썼는지 찍히고, 파일이 없으면 기본값을 씁니다.

시도해볼 만한 변형들:

```
console=ttyGF0 earlycon=pl011,0x09000000 keep_bootcon ignore_loglevel androidboot.hardware=ranchu androidboot.selinux=permissive binder.devices=binder,hwbinder,vndbinder rdinit=/init.wrapper root=/dev/ram0 rw
```
```
console=ttyAMA0 earlycon keep_bootcon ignore_loglevel androidboot.hardware=ranchu androidboot.selinux=permissive rdinit=/init root=/dev/ram0 rw
```

두 번째는 `/init.wrapper`를 건너뛰고 원래 `/init`으로 바로 가는 것으로, **래퍼가
문제인지 커널이 문제인지 가르는** 데 씁니다.

### 3.3 커널 소스

cmdline의 `kernel-android54`와 `binder.devices=`를 보면 **android-5.4 커널**을
겨냥하신 것으로 보입니다.

```sh
git clone https://android.googlesource.com/kernel/common -b android11-5.4
# ARCH=arm, virt 보드 대상으로 빌드
```

SDK의 `kernel-ranchu`를 먼저 시도해볼 가치는 있습니다. ranchu 보드 자체가 QEMU
`virt`를 바탕으로 만들어졌기 때문에 PL011 콘솔·virtio-mmio는 맞을 가능성이 높습니다.
MMIO로 통일했으므로 PCI 문제는 사라졌고, 남은 관건은 `CONFIG_VIRTIO_CONSOLE`
포함 여부입니다. **먼저 SDK 커널로 6절 스모크 테스트를 돌려보고,
`/dev/vport0p0` 유무로 판단하는 게 가장 빠릅니다.**

---

## 4. 램디스크와 `/init.wrapper`

### 4.1 왜 스크립트가 아니라 바이너리여야 하나

`rdinit=/init.wrapper`는 **rdinit 시점**에 실행됩니다. 이때는 `/system`이 아직
마운트되지 않아 셸(`/system/bin/sh`, toybox)이 없습니다. 따라서 `#!/bin/sh`
스크립트는 동작하지 않습니다.

**정적 링크된 ARM32 실행 파일**이어야 합니다. 호스트 쪽 `libpodroid-launcher.so`와
같은 성격입니다.

역할은 이렇습니다:

1. `/dev` 준비, `/dev/vport0p*` 가 뜰 때까지 대기
2. `/dev/qemu_pipe`(및 `/dev/goldfish_pipe`) 요청을 virtio-serial 포트로 연결
3. 원래 `/init`을 `execv`로 넘김 (argv 보존)

소스와 빌드 스크립트가 저장소에 있습니다:

```sh
cd guest/init_wrapper
ANDROID_NDK_HOME=~/Android/Sdk/ndk/27.0.12077973 ./build.sh
```

자세한 설명은 [../guest/README.md](../guest/README.md). 1차 버전은 vport 존재 확인과
로그만 하고 `/init`으로 넘깁니다 — 아래 4.2의 다중화는 아직 하지 않습니다.

### 4.2 ⚠ 파이프 다중화 문제

**단순 심볼릭 링크로는 안 됩니다.**

goldfish pipe는 **열 때마다 새 채널**이 생깁니다. HAL 여러 개가 각자 파이프를
동시에 엽니다. 반면 virtio-serial 포트는 **한 번에 하나만** 열립니다. 파이프가
8개인 이유가 이것입니다.

심볼릭 링크는 항상 같은 vport를 가리키므로 **두 번째 클라이언트부터 막힙니다.**
따라서 `open("/dev/qemu_pipe")`를 가로채 **비어 있는 vport로 분배하는 계층**이
필요합니다. 후보:

| 방식 | 장점 | 단점 |
|---|---|---|
| LD_PRELOAD 셔임 | 커널 불필요, 구현 쉬움 | 정적 링크 바이너리엔 안 먹음 |
| FUSE로 `/dev/qemu_pipe` 제공 | 정확한 의미 재현 | 부팅 초기에 FUSE 띄우기가 까다로움 |
| 작은 커널 모듈 | 가장 정확 | 커널 빌드와 묶임 |
| 동시 파이프 8개로 제한 + HAL 패치 | 단순 | system.img 수정 필요 |

**동시에 열리는 파이프가 실제로 몇 개인지 먼저 측정**하는 게 순서입니다. 8개로
충분하다면 분배만 해주면 되고, 그렇지 않다면 파이프 수를
`TRANSPORT_PIPE_COUNT`에서 늘리면 됩니다.

### 4.3 램디스크 재패킹

```sh
mkdir rd && cd rd
zcat ../ramdisk.img | cpio -idmv          # 기성 램디스크 풀기
cp ../init.wrapper .                       # 래퍼 추가
chmod 750 init.wrapper
find . | cpio -o -H newc | gzip > ../ramdisk-new.img
```

---

## 5. 임포터 규격에 맞춰 ZIP 만들기

앱의 `MainActivity.importAndroid7Bundle()`이 ZIP 안에서 **파일명으로** 찾습니다
(경로는 무시하고 basename만 봅니다):

| 찾는 이름 | 필수 | 저장될 이름 |
|---|---|---|
| `kernel-ranchu` → `kernel-qemu` → `kernel` (이 순서) | ✅ | `kernel` |
| `ramdisk.img` | ✅ | `ramdisk.img` |
| `system.img` | ✅ | `system.img` |
| `userdata.img` | ❌ | `userdata.img` |

```sh
zip -0 android7-guest.zip kernel ramdisk.img system.img userdata.img
```

`-0`(무압축)을 쓰면 임포트가 빠릅니다. 앱은 압축 해제 공간으로 **원본 크기 +
256MB**를 요구합니다.

> 참고: `kernel-android54` / `ramdisk-android54.img` 이름을 쓰면 앱이 `-cpu`를
> `cortex-a53`으로 잡습니다. Android 7 **ARM32** 게스트는 `cortex-a15`가 맞으므로
> 파일명을 `kernel` / `ramdisk.img`로 두세요.

---

## 6. PC 스모크 테스트 (앱이 만드는 명령줄과 동일)

`/tmp/guest`에 `kernel`, `ramdisk.img`, `system.img`, `userdata.img`를 두고:

```sh
cd /tmp/guest && mkdir -p transport

ARGS=(
  -machine virt,gic-version=3
  -cpu cortex-a15
  -accel tcg,thread=multi,tb-size=256
  -smp 2 -m 1024
  -display none
  -device virtio-gpu-device,xres=480,yres=800
  -device virtio-serial-device
)
for i in 0 1 2 3 4 5 6 7; do
  ARGS+=(-chardev "socket,id=singlevmpipe$i,path=/tmp/guest/transport/pipe$i.sock,server=on,wait=off")
  ARGS+=(-device "virtserialport,chardev=singlevmpipe$i,name=org.singlevm.pipe.$i")
done
ARGS+=(
  -monitor none -nic none -serial stdio
  -kernel /tmp/guest/kernel
  -initrd /tmp/guest/ramdisk.img
  -drive "if=none,id=userdata,format=raw,file=/tmp/guest/userdata.img"
  -device virtio-blk-device,drive=userdata
  -drive "if=none,id=system,format=raw,readonly=on,file=/tmp/guest/system.img"
  -device virtio-blk-device,drive=system
  -append "console=ttyAMA0 androidboot.hardware=ranchu androidboot.selinux=permissive binder.devices=binder,hwbinder,vndbinder rdinit=/init.wrapper root=/dev/ram0 rw"
)

# 앱과 동일하게: 64비트 QEMU 바이너리가 32비트 CPU(cortex-a15)를 에뮬레이트한다.
# qemu-system-arm 이 아님에 주의 — 앱은 libqemu-system-aarch64.so 를 쓴다.
qemu-system-aarch64 "${ARGS[@]}"
```

> 유닉스 소켓 경로는 **108바이트 제한**이 있습니다. 긴 경로에서는 QEMU가
> `UNIX socket path ... is too long`으로 죽습니다. 짧은 디렉터리를 쓰세요.
> (안드로이드 실제 경로는 약 70바이트라 문제없습니다.)

---

## 7. 단계별 성공 판정 — 실측 현황

아래 표는 계획이 아니라 **CI(`.github/workflows/guest-image.yml`)에서 실제로 확인한
결과**입니다. 각 관문에서 걸렸던 원인과 그 근거가 된 로그 한 줄을 같이 적어둡니다.
같은 자리에서 다시 막혔을 때 추측을 반복하지 않기 위한 기록입니다.

| # | 목표 | 상태 | 걸렸던 원인 |
|---|---|---|---|
| 1 | 커널이 뜬다 | ✅ | SDK `kernel-ranchu` 는 goldfish 보드용이라 `-machine virt` 에서 무음으로 죽음 → 직접 빌드 |
| 2 | 램디스크를 잡는다 | ✅ | |
| 3 | `/init.wrapper` 가 돈다 | ✅ | |
| 4 | virtio-serial 이 보인다 | ✅ | `/dev/vport0p0` 은 영영 안 생김 — 포트 0 은 콘솔 예약. 실제로는 `vport3p1..p8` |
| 5 | `/init` 이 넘겨받는다 | ✅ | `CONFIG_LSM` 에 selinux 가 없으면 selinuxfs 미등록 → 정책 로드 실패 |
| 6 | `/system` `/cache` `/data` 마운트 | ✅ | **장치 순서** (아래 7.1) |
| 7 | zygote / system_server 기동 | ✅ | **바인더** (아래 7.2) |
| 8 | SurfaceFlinger | ❌ | `SurfaceFlinger::init()` 에서 abort — 아래 7.3 |
| 9 | emugl 연결 | ❌ | 8번에 막혀 도달 못 함 |

### 7.1 virtio-mmio 는 커맨드라인 순서를 보장하지 않는다

`-drive` 를 system → cache → userdata 순으로 적었는데 게스트는
`vda=userdata(2G)`, `vdb=cache(256M)`, `vdc=system(1.75G)` 로 봤습니다. 바깥 둘이
뒤집힙니다. 결과:

- `/system` 이 **빈 userdata 를 물었습니다.** 빈 ext4 도 `ro` 마운트는 성공하므로
  로그상으로는 정상으로 보였습니다.
- `/data` 는 `readonly=on` 으로 붙인 system.img 를 가리켰고, 쓰기 마운트가
  거부되며 `error: Permission denied`(EACCES) 로 실패했습니다.
- `/sys/block/vda/ro` 가 1 이었던 것은 fs_mgr 때문입니다. fstab 항목이 `ro` 면
  fs_mgr 이 그 장치에 `BLKROSET` 을 겁니다.

호스트 QEMU 버전마다 달라질 수 있으므로 **순서를 맞추는 방식은 쓰지 않습니다.**
`init.wrapper` 가 각 디스크의 ext4 슈퍼블록 라벨(`system` / `cache` / `data`)을 읽어
`fstab.ranchu` 의 장치 이름을 실제 장치로 고쳐 씁니다. 안드로이드 init 보다 먼저
도는 시점이라 rootfs 가 아직 쓰기 가능합니다.

> `mkfs.ext4 -L` 로 cache/userdata 에 라벨을 주고, SDK 의 system.img 에는
> `e2label` 로 지정합니다. 라벨이 없으면 교정이 동작하지 않습니다.

### 7.2 바인더는 두 번 막힙니다

**(a) 장치가 안 생김.** `CONFIG_ANDROID_BINDERFS=y` 면 binderfs 가
`CONFIG_ANDROID_BINDER_DEVICES` 의 이름을 가져가서 마운트된 binderfs 안에만 장치를
만듭니다. legacy misc 장치가 등록되지 않아 `/dev/binder` 가 없습니다.

판별법 — misc 클래스에 ashmem 은 있는데 binder 가 없고, `/proc/filesystems` 에는
binder 가 있음:

```
wd dev: /sys/class/misc/binder MISSING
wd dev: binderfs in /proc/filesystems: yes
wd dev: misc class: vga_arbiter hw_random autofs rfkill cpu_dma_latency ashmem ...
```

Android 7 은 binderfs 를 모르고 `/dev/binder` 를 직접 엽니다. → **BINDERFS 를 끕니다.**

**(b) ABI 가 안 맞음.** 장치가 생긴 뒤에도 이렇게 죽습니다:

```
E ProcessState: Binder driver protocol does not match user space protocol!
F ProcessState: Binder driver could not be opened.  Terminating.
init: critical process 'servicemanager' exited 4 times in 4 minutes; rebooting into recovery mode
```

API 24 armeabi-v7a 유저스페이스는 옛 **32비트 바인더 API(프로토콜 7**, 바인더 구조체
안 포인터가 32비트)로 빌드돼 있습니다. `BINDER_IPC_32BIT` 는 이후 커널에서 삭제돼
5.10 은 프로토콜 8 + 64비트 포인터만 제공합니다.

드라이버가 전부 `binder_uintptr_t` / `binder_size_t` 타입으로 쓰여 있으므로,
`include/uapi/linux/android/binder.h` 의 타입 정의 두 줄과 버전 상수 한 줄을 되돌리면
드라이버 전체가 32비트 ABI 로 컴파일됩니다. `guest/kernel/build-kernel.sh` 가 이
치환을 하고, **치환이 안 먹으면 빌드를 실패시킵니다** — 조용히 잘못된 ABI 의 커널을
만드는 것이 가장 나쁩니다.

### 7.3 지금 막혀 있는 곳: SurfaceFlinger

```
F DEBUG: pid: 4665, name: surfaceflinger >>> /system/bin/surfaceflinger <<<
F DEBUG:   #06 pc 00025e4d /system/lib/libsurfaceflinger.so (_ZN7android14SurfaceFlinger4initEv+280)
init: Service 'surfaceflinger' killed by signal 6
```

Android 7 의 init.rc 는 surfaceflinger 에 `onrestart restart zygote` 를 걸어둡니다.
그래서 SF 가 죽을 때마다 zygote 와 그 의존 서비스가 통째로 재시작되고, 부팅이
영원히 같은 자리를 맴돕니다(PID 가 10000 을 넘고, zygote 는 895초 시점에도 아직
"Preloading classes" 였습니다). **부팅이 느린 것이 아니라 계속 처음부터 다시 하는
것**이라는 점을 착각하기 쉽습니다.

`SurfaceFlinger::init()` 은 EGL/gralloc 설정 실패에 `LOG_ALWAYS_FATAL` 을 겁니다.
ranchu 그래픽 HAL 은 `/dev/qemu_pipe` 로 호스트 렌더러에 붙는데, 이 게스트에는 goldfish
pipe 드라이버도 장치도 없습니다 — 4.2 에서 미룬 바로 그 전송로입니다.

### 7.4 치명적이지 않은 잡음 (무시해도 됨)

부팅 로그에 계속 나오지만 진행을 막지 않는 것들입니다. 여기에 시간을 쓰지 마세요.

- `cgroup: Unknown subsys name 'schedtune'` — `SCHED_TUNE` 은 5.10 에서 uclamp 로
  대체되며 삭제됐습니다. `--enable` 해도 조용히 무시됩니다.
- `memtrack` / `radio.primary` / `sound_trigger.primary` / `audio.primary` 로드 실패
  — 해당 HAL 이 이미지에 없습니다.
- `avc: denied ... permissive=1` — permissive 라 차단이 아니라 기록만 된 것입니다.
- `Permission ... not defined in policy` — 5.10 이 정의하는 권한을 Android 7 정책이
  모를 뿐입니다.

### 7.5 진단 도구

추측을 반복하지 않기 위해 게스트 안에 넣어둔 것들입니다.

- **감시자(watchdog)**: `init.wrapper` 가 `/init` 으로 exec 하기 직전 fork 해서 남기는
  프로세스. exec 전에 열어둔 `/dev/kmsg` fd 를 그대로 들고 있어(안드로이드 init 이
  `/dev` 에 tmpfs 를 새로 덮어도 이미 열린 fd 는 유효합니다) 5초마다 마운트 상태,
  블록 장치, PID 1 의 wchan/syscall, 그래픽·바인더 장치 유무를 찍습니다.
  **콘솔이 조용해졌을 때 "멈춘 것"과 "로그를 안 내는 것"을 구분할 방법이 이것뿐입니다.**
- **`printk.devkmsg=on`**: 커널은 유저스페이스의 `/dev/kmsg` 쓰기를 기본값으로 속도
  제한합니다. init 과 fs_mgr 이 바로 이 경로로 로그를 냅니다. 이걸 켜기 전까지
  fs_mgr 의 마운트 실패 이유가 콘솔에 **한 번도** 나오지 않았습니다.
- **`seriallogcat`**: ueventd 이후 안드로이드는 logcat 으로만 로그를 남깁니다.
  조립 단계에서 init.rc 에 logcat 을 콘솔로 흘리는 서비스를 심습니다.

---

## 8. 폰으로 옮긴 뒤

PC에서 7번까지 통과하면 ZIP으로 묶어 앱에서 **설정 → Android 7 구성 가져오기**로
임포트합니다.

부팅 로그는 두 곳에 남습니다:

- 화면에 실시간 스트리밍 (5초 내 종료 시 원인 후보를 앞으로 끌어냄)
- `Android/data/com.example.singlevm/files/logs/qemu-modern.log`
  — **루팅·adb 없이 파일 관리자로 열립니다**
