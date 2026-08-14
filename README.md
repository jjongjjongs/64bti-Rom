# singlevm — 단일 32비트 가상롬

VPhoneOS류의 안드로이드 가상 ROM 컨테이너 앱. QEMU aarch64 기반으로 게스트 안드로이드
환경을 돌려 32bit 앱 실행/호환성을 확보하는 것이 목표.

## 이 저장소의 출처

원본 Android Studio 프로젝트 소스가 유실되어, 빌드된 APK(`단일_32비트_가상롬.apk`,
versionName 0.2.0)를 [jadx](https://github.com/skylot/jadx) 1.5.6으로 역디컴파일해서
복구한 상태입니다. 정확도 등급은 파일마다 다릅니다.

| 구성 | 상태 |
|---|---|
| `app/src/main/java/com/example/singlevm/**` | jadx로 복구한 커스텀 코드. 컴파일 가능한 수준으로 읽히지만, 원본 변수명/주석은 유실됨 |
| `app/src/main/java/org/libsdl/app/**` | SDL2 안드로이드 프로젝트의 표준 Java 글루 코드 (zlib 라이선스, 커스텀 아님). **추후 [libsdl-org/SDL](https://github.com/libsdl-org/SDL) 공식 소스로 교체 권장** |
| `app/src/main/AndroidManifest.xml`, `res/**` | jadx가 실제로 디코딩한 리소스 — 원본과 동일 |
| `app/src/main/jniLibs/arm64-v8a/*.so` | apk에서 그대로 추출한 **컴파일된 바이너리**. 디컴파일 불가 (기계어) |

## 네이티브 라이브러리 정체 (교체 대상)

`.so` 바이너리는 소스가 아니라 다음 오픈소스 프로젝트들의 빌드 산출물로 추정됩니다.
빌드를 진짜 소스 기반으로 되돌리려면 아래를 서브모듈로 넣고 다시 빌드해야 합니다.

- `libqemu-system-aarch64.so`, `libslirp.so` → [qemu/qemu](https://github.com/qemu/qemu) (버전 확인 필요)
- `libglib-2.0.so` → GLib
- `libpixman-1.so` → pixman
- `libSDL2.so`, `libcompat-SDL2-*.so` → [libsdl-org/SDL](https://github.com/libsdl-org/SDL)
- `libcompat-limbo.so`, `libcompat-musl.so` → Limbo(QEMU 안드로이드 프론트엔드) 계열로 추정, 정확한 포크 확인 필요
- `libemugl_host_android.so`, `libemugl_probe.so` → AOSP 에뮬레이터 emugl (GPU 에뮬레이션)
- `libpodroid-launcher.so`, `libsinglevm_qemu.so`, `libsinglevm_runtime.so` → **커스텀 글루 코드로 추정, 소스 없음 (재작성 필요)**

## 앱 구조 (jadx 복구 기준)

- `MainActivity` — 게스트 이미지 목록/설정 UI (1589줄)
- `GuestRunActivity` — 실제 VM 구동, 별도 프로세스(`:vm`)로 실행 (매니페스트에서 확인)
- `engine/EngineRegistry` — 두 엔진 어댑터를 등록/선택
  - `Android7GuestEngineAdapter` — QEMU 기반 최신(Android7) 게스트
  - `LegacyArmEngineAdapter` — libcompat-limbo/musl 기반 레거시 ARM 호환 경로
- 외부 의존성: androidx/Compose 없음, 순수 `android.*` SDK + `org.json` (jadx import 기준 확인)

## 알려진 리소스 (매니페스트 실측값)

- `applicationId` / `namespace`: `com.example.singlevm`
- `minSdk 24` / `targetSdk 34` / `compileSdk 34`
- `versionCode 1`, `versionName "0.2.0"`
- `debuggable=true` (원본 apk 자체가 디버그 빌드였음 — release 빌드 전 반드시 false로)

## TODO

- [ ] `MainActivity` / `GuestRunActivity` 실제 로직 리뷰 (jadx 결과라 변수명이 `p0`, `str2` 등으로 난해한 부분 있음)
- [ ] 네이티브 라이브러리 진짜 버전/커밋 특정 후 서브모듈로 교체
- [ ] `libpodroid-launcher.so` / `libsinglevm_*.so` 재작성 (또는 원 작성자 확인)
- [ ] `debuggable=true` → release 빌드 시 제거
