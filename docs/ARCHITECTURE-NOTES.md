# 아키텍처 검토 — "32비트 전용 게임 APK를 실행한다"는 목표에 대해

이 문서는 저장소 코드와 바이너리를 실측한 결과, 그리고 목표 달성에 필요한 구조적
판단을 정리한 것입니다. 코드 정리나 빌드 설정과 달리 **아직 결정되지 않은 것**을
다룹니다.

**이 저장소의 범위**: APK 형태로 배포된 32비트 전용 안드로이드 게임을, 32비트
가상롬 안에서 실행시키는 것. (LGT 피처폰 WIPI 게임 구동은 별개 프로젝트이며 이
저장소와 무관합니다.)

## 지금 게임이 실행되지 않는 이유

빌드는 됩니다. 게스트를 띄우는 것도 코드상으로는 시도합니다. 그런데 게임이
실행될 수 없는 **구조적** 이유가 세 가지 있습니다.

### 1. 게임 APK를 게스트로 넘기는 통로가 없다

`MainActivity.launchGuestWithEngine()`은 `EXTRA_APK_PATH`, `EXTRA_LIB_DIR`,
`EXTRA_DATA_DIR`, `EXTRA_PACKAGE`, `EXTRA_LAUNCHER`를 Intent에 담아
`GuestRunActivity`로 넘깁니다. 그런데 이 값들이 실제로 쓰이는 곳은
`startGuestRuntime()` 안의 **로그 문자열 조립 한 군데뿐**입니다.

`buildModernQemuCommand()`와 `startAndroid7Guest()`에서 이 extra들의 참조 횟수는
**0회**입니다. 생성되는 QEMU 명령줄에는 `-virtfs`도 `-fsdev`도 없고, `-drive`는
`userdata` / `cache` / `system` 셋뿐이며 `-nic none`이라 네트워크도 없습니다.

즉 **게스트가 완벽하게 부팅해도 어떤 게임을 실행해야 하는지 알 방법이 없습니다.**
호스트는 VM을 띄울 뿐이고, 그 안에 APK를 넣는 경로가 존재하지 않습니다.

### 2. 게스트 쪽 절반이 저장소에 없다

커널 cmdline이 `rdinit=/init.wrapper`를 요구합니다. 즉 램디스크에 `/init.wrapper`가
있는 **전용 게스트 이미지**가 전제입니다. 저장소에도 APK에도 그런 이미지는 없고
(APK에 `assets/`가 아예 없음), 만드는 스크립트도 없습니다.
`MainActivity.importAndroid7Bundle()`은 사용자가 준 ZIP에서 kernel/ramdisk/system을
**그대로 꺼내 쓸 뿐** 가공하지 않으므로, 표준 에뮬레이터 이미지를 넣으면
`/init.wrapper`가 없어 부팅 즉시 실패합니다.

같은 이야기가 그래픽에도 적용됩니다. `-display none` + `virtio-gpu-pci` 조합에
virtio-serial 파이프 8개(`org.singlevm.pipe.0~7`)를 깔아두고 호스트에서
`libemugl_probe.so`가 붙지만, **그 반대편(게스트 안에서 렌더 명령을 파이프로 보내는
컴포넌트)이 없습니다.** 표준 Android 7 시스템 이미지에는 당연히 없습니다.

입력(터치 → 게스트)도 마찬가지로 어디에도 구현되어 있지 않습니다.

### 3. 게스트 하드웨어 계약이 모순이다 (ranchu vs virt)

번들된 `libqemu-system-aarch64.so`(QEMU 11.0.2)를 조사한 결과, **업스트림 QEMU
빌드**입니다. 머신 타입은 `virt` 하나뿐이고 `ranchu`가 없습니다.
`goldfish_rtc` / `goldfish_tty` / `goldfish_pic` 심볼이 보이지만 이건 업스트림이
RISC-V · MIPS 보드용으로 가지고 있는 것이고, 안드로이드 에뮬레이터가 요구하는
**`goldfish_fb`(프레임버퍼), `goldfish_pipe`(호스트 통신·GPU), `goldfish_events`(입력)는
존재하지 않습니다.**

그런데 `buildModernQemuCommand()`가 넘기는 커널 cmdline은
`androidboot.hardware=ranchu` 입니다. 실제 제공 하드웨어는 virtio 계열
(`virtio-gpu-pci`, `virtio-blk-device`, `virtserialport`)인데 게스트에게는
goldfish 하드웨어라고 선언하는 셈입니다.

**결과: 어떤 이미지를 넣어도 맞지 않습니다.**

- 표준 에뮬레이터(ranchu) 시스템 이미지 → init은 올라와도 HAL이 goldfish 장치를
  찾지 못해 부팅이 완료되지 않습니다.
- virtio 대상으로 만든 이미지 → `androidboot.hardware=ranchu`가 잘못된 값입니다.

#### 이게 왜 이렇게 됐는가 (툴체인 증거)

앞서 확인한 두 툴체인 세대가 이 모순을 설명합니다.

| 라이브러리 | NDK | 설계 방향 |
|---|---|---|
| `libemugl_host_android.so`, `libemugl_probe.so`, `libsinglevm_*` | r26 (clang 17.0.2) | AOSP 에뮬레이터 emugl = **ranchu/goldfish_pipe 전제** |
| `libqemu-system-aarch64.so`, `libslirp.so`, `libpodroid-launcher.so` | r27 (clang 18.0.3) | **업스트림 QEMU = virt 전용** |

즉 원래 설계는 **ranchu + goldfish_pipe + emugl**(구글 에뮬레이터 스택)이었는데,
나중에 QEMU만 업스트림 빌드로 교체되면서 ranchu 계약이 깨진 것으로 보입니다.
emugl 라이브러리들이 저장소에 남아 있는 것이 원래 방향의 증거입니다.

#### 선택지

1. **ranchu 가능한 QEMU로 되돌린다** — 구글의 `qemu-android` 포크를 arm64 안드로이드용으로
   빌드해서 `libqemu-system-aarch64.so`를 교체. 그러면 기존 cmdline·emugl·표준 Android 7
   ARM32 에뮬레이터 시스템 이미지가 전부 아귀가 맞습니다. **원 설계로의 복귀**이고,
   기성 이미지를 쓸 수 있다는 게 가장 큰 장점입니다.
2. **virtio 대상 게스트를 새로 만든다** — 현재 하드웨어 구성에 맞춰 AOSP를 빌드.
   `androidboot.hardware`도 그에 맞게 바꿔야 합니다. 기성 이미지가 없어 부담이 큽니다.
3. **전면 에뮬레이션을 포기한다** — 위 (A) 시나리오면 앱 레벨 가상화가 훨씬 빠릅니다.

### 4. 성능

`-accel tcg,thread=multi` — TCG는 순수 소프트웨어 번역입니다. 휴대폰에서 Android 7
전체를 TCG로 돌리면 부팅에만 수 분이 걸리고, 3D 게임은 현실적으로 플레이 가능한
프레임이 나오지 않습니다. KVM은 안드로이드 앱 권한으로 접근할 수 없습니다.

## 참고: VPhoneOS(VPhoneGaGa)는 다른 방식이다

업로드된 `VPhoneOS.apk`(실제 패키지 `com.vphonegaga.titan`)를 분석한 결과입니다.
※ 업로드가 20.2MB에서 잘려 있어 `lib/` 부분은 확인하지 못했습니다. 아래는 확인된
범위의 사실입니다.

**매니페스트에 `MyNativeActivity1` ~ `MyNativeActivity570`, 스텁 액티비티 570개가
선언되어 있습니다.** 이건 전면 시스템 에뮬레이션이 아니라 **앱 레벨 가상화**
(VirtualApp / VirtualXposed 계열)의 명확한 지문입니다. 게스트 앱의 액티비티를
런타임에 이 껍데기 액티비티들에 매핑하는 방식으로, QEMU도 게스트 커널도 시스템
이미지도 쓰지 않습니다.

`assets/`에는 별개로 `wine_data/dxvk-*`, `vcrun2019/*`, `OpenAL/*`,
`freedreno-*/libvulkan_freedreno.so`, `kb_map/*`가 있습니다. 이건 **Windows x86/x64
게임**을 Wine + DXVK + Turnip으로 돌리는 Winlator 계열 기능이며, 32비트 안드로이드
앱 문제와는 무관합니다.

**결론: VPhoneGaGa는 이 저장소가 하려는 것(QEMU 전면 에뮬레이션)을 하지 않습니다.**
안드로이드 앱은 컨테이너 가상화로, PC 게임은 Wine으로 각각 처리합니다. 따라서
현재 접근의 참고 모델로는 적절하지 않습니다.

## 갈림길: 대상 기기가 32비트 ARM을 실행할 수 있는가

이게 모든 것을 가릅니다.

```sh
adb shell getprop ro.product.cpu.abilist
```

### (A) `armeabi-v7a`가 목록에 있다 → 에뮬레이션이 필요 없다

기기가 32비트 프로세스를 만들 수 있다는 뜻입니다. 이 경우 **앱 레벨 가상화**가
압도적으로 낫습니다:

- 컨테이너 프로세스를 32비트로 띄우고 게스트의 `armeabi-v7a` `.so`를 그대로
  로드하면 **네이티브 속도**로 돕니다. 번역도 에뮬레이션도 없습니다.
- 필요한 것: 스텁 액티비티 풀, PackageManager/ActivityManager 후킹, 게스트 앱의
  dex/so 로딩. QEMU·커널·시스템 이미지는 전부 불필요합니다.
- VPhoneGaGa가 택한 길이고, 570개 스텁이 그 증거입니다.

이 경우 현재의 QEMU 경로는 **목표 달성에 필요 없는 부분**이 됩니다.

### (B) `armeabi-v7a`가 없다 (64비트 전용 기기)

2023년 이후 코어(Cortex-X4 / A720 / A520 계열)만으로 구성된 SoC는 EL0에서 AArch32를
아예 실행할 수 없습니다. 하드웨어가 32비트 명령을 디코드하지 못하므로 (A)는
**원천적으로 불가능**합니다. 이때 선택지는:

1. **유저스페이스 ARM32→ARM64 번역** — 32비트 bionic/linker와 프레임워크만 번역
   계층 위에 올리는 방식. 전면 시스템 에뮬레이션보다 훨씬 빠릅니다. 다만 32비트
   안드로이드 런타임 일부를 직접 구성해야 해서 난이도가 높습니다.
2. **전면 시스템 에뮬레이션(현재 접근)** — 구현 부담이 가장 크고 성능이 가장
   나쁩니다. 게임 플레이 목적으로는 현실성이 낮습니다.

## 참고할 만한 오픈소스

VPhoneGaGa 홍보 사이트가 스스로 지목하는 경쟁 앱은 VMOS Pro, X8Sandbox, **Twoyi**
입니다. 이 중 Twoyi만 오픈소스이고, 이 프로젝트의 목표에 가장 가까운 구조입니다.

- **[Twoyi](https://github.com/twoyi/twoyi)** — 루트 없이, 부트로더 언락 없이, 시스템
  수정 없이 **거의 완전한 안드로이드 시스템을 일반 앱으로** 돌리는 컨테이너.
  내부 안드로이드는 8.1(10 지원 예정)이고 **부팅이 3초 내**입니다. 시스템이
  오픈소스라 포크해서 직접 컴파일하고 HAL 계층까지 커스터마이즈할 수 있습니다.

QEMU TCG로 Android 7을 부팅하면 수 분이 걸리는 것과 비교하면 차이가 분명합니다.
Twoyi가 빠른 이유는 **에뮬레이션을 하지 않기 때문**입니다 — 호스트 커널 위에서
안드로이드 유저스페이스만 따로 띄웁니다.

다만 **바로 그 이유로 Twoyi도 64비트 전용 기기 문제를 해결하지는 못합니다.** 호스트
CPU를 그대로 쓰므로, CPU가 AArch32를 실행하지 못하면 32비트 게스트 앱도 실행할 수
없습니다. 즉 Twoyi는 위 (A) 시나리오의 정답이지 (B)의 정답은 아닙니다.

또한 VPhoneGaGa가 컨테이너를 **32비트/64비트 별도 빌드**로 배포하고 게스트 ROM으로
Android 7과 10을 제공한다는 점도 같은 구조를 뒷받침합니다. 32비트 빌드를 따로 내는
이유가 게스트의 `armeabi-v7a` 라이브러리를 네이티브로 로드하기 위해서입니다.

## 권고

1. **먼저 `ro.product.cpu.abilist`를 확인할 것.** (A)라면 지금 QEMU에 들이는 노력의
   대부분이 불필요합니다.
2. QEMU 경로를 계속 간다면, 순서는 **부팅 성공 → 파일 전달 통로 → 게스트 에이전트
   → 렌더/입력**입니다. 지금은 1단계도 확인되지 않은 상태이며, 그 원인이 화면에
   보이지 않는 것이 가장 큰 문제였습니다(아래).
3. 진단 가시성은 개선했습니다. QEMU 출력이 화면에 실시간으로 흐르고, 5초 안에
   죽으면 원인 후보와 마지막 출력을 앞으로 끌어냅니다. 로그 사본이
   `Android/data/com.example.singlevm/files/logs/qemu-modern.log`에 남으므로
   루팅·adb 없이 파일 관리자로 열 수 있습니다.

## 확인된 사실 요약 (실측)

| 항목 | 값 | 확인 방법 |
|---|---|---|
| QEMU 명령줄 유효성 | 정상 | QEMU 8.2.2로 동일 인자 재현, 20초 정상 구동 |
| `-cpu cortex-a15` + `gic-version=3` | 정상 | 위와 동일 (문제 아님) |
| APK→게스트 전달 경로 | **없음** | extra 참조 0회, `-virtfs`/`-netdev` 없음 |
| 게스트 이미지 | **저장소·APK에 없음** | APK에 `assets/` 자체가 없음 |
| dex 클래스 복구율 | 65/65 (100%) | APK dex 파싱 후 저장소와 대조 |
