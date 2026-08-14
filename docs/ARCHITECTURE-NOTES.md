# 아키텍처 검토 — "32비트 전용 게임 APK를 실행한다"는 목표에 대해

이 문서는 저장소 코드와 바이너리를 실측한 결과, 그리고 목표 달성에 필요한 구조적
판단을 정리한 것입니다. 코드 정리나 빌드 설정과 달리 **아직 결정되지 않은 것**을
다룹니다.

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

### 3. 성능

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
