# 게스트 이미지 만들기

호스트(앱) 쪽은 대체로 서 있습니다. 지금 비어 있는 건 **게스트 이미지**이고, 이
문서는 그걸 만드는 절차입니다.

배경과 판단 근거는 [ARCHITECTURE-NOTES.md](ARCHITECTURE-NOTES.md)에 있습니다.
요약하면 — QEMU는 손댈 필요 없고, 게스트가 표준 **ranchu HAL**을 로드한 뒤
`/dev/qemu_pipe`를 열면 `/init.wrapper`가 그걸 virtio-serial로 돌려주고, 호스트의
`libemugl_probe.so`가 받아서 렌더링하는 구조입니다.

---

## 0. 대원칙: 폰이 아니라 PC에서 먼저 잡는다

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

## 7. 단계별 성공 판정

한 번에 다 되기를 기대하지 말고, 아래 순서로 하나씩 통과시키세요.

| # | 목표 | 성공 신호 |
|---|---|---|
| 1 | 커널이 뜬다 | 시리얼에 `Booting Linux on physical CPU` |
| 2 | 램디스크를 잡는다 | `Unpacking initramfs` 후 패닉 없음 |
| 3 | `/init.wrapper`가 돈다 | 래퍼가 찍는 로그 (직접 넣으세요) |
| 4 | **virtio-serial이 보인다** | `/dev/vport0p0` 존재 ← 3.2 관문 |
| 5 | `/init`이 넘겨받는다 | Android init 로그, `init: init first stage started` |
| 6 | system.img 마운트 | `/system` 마운트 성공 |
| 7 | zygote 기동 | `Zygote: Process ... starting` |
| 8 | emugl 연결 | 호스트 `libemugl_probe.so`에 `pipe:opengles` 도달 |

**4번이 진짜 관문입니다.** 여기서 막히면 커널의 `CONFIG_VIRTIO_CONSOLE`을
확인하세요 (호스트 쪽 버스 문제는 MMIO 통일로 해결됐습니다).

TCG 소프트웨어 에뮬레이션이라 PC에서도 부팅에 수 분 걸립니다. 폰은 더 느립니다.
`-serial stdio`라 로그가 그대로 보이니 인내심 있게 지켜보세요.

---

## 8. 폰으로 옮긴 뒤

PC에서 7번까지 통과하면 ZIP으로 묶어 앱에서 **설정 → Android 7 구성 가져오기**로
임포트합니다.

부팅 로그는 두 곳에 남습니다:

- 화면에 실시간 스트리밍 (5초 내 종료 시 원인 후보를 앞으로 끌어냄)
- `Android/data/com.example.singlevm/files/logs/qemu-modern.log`
  — **루팅·adb 없이 파일 관리자로 열립니다**
