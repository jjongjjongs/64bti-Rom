#!/usr/bin/env bash
# init.wrapper 빌드 — 정적 링크 ARM32 실행 파일
#
# rdinit 시점에는 /system 이 마운트되지 않아 셸도 동적 링커도 기대할 수 없으므로
# 반드시 정적 링크여야 한다.
#
# 사용법:
#   ANDROID_NDK_HOME=~/Android/Sdk/ndk/27.0.12077973 ./build.sh
#
# NDK 가 없으면 arm-linux-gnueabihf-gcc(glibc 크로스)로 폴백한다. 문법·동작 검증에는
# 충분하지만, 게스트에 실제로 넣을 바이너리는 NDK(bionic)로 빌드하는 것을 권장한다.
set -euo pipefail

SRC_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="${1:-$SRC_DIR/init.wrapper}"
API="${API_LEVEL:-24}"   # 게스트가 Android 7.0 = API 24

if [ -n "${ANDROID_NDK_HOME:-}" ]; then
    CC="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/armv7a-linux-androideabi${API}-clang"
    [ -x "$CC" ] || { echo "NDK 컴파일러를 찾을 수 없습니다: $CC" >&2; exit 1; }
    echo "==> NDK(bionic) 로 빌드: API $API"
elif command -v arm-linux-gnueabihf-gcc >/dev/null 2>&1; then
    CC=arm-linux-gnueabihf-gcc
    echo "==> 폴백: arm-linux-gnueabihf-gcc (glibc). 검증용으로만 쓰세요."
else
    echo "ARM 컴파일러가 없습니다. ANDROID_NDK_HOME 을 설정하거나" >&2
    echo "gcc-arm-linux-gnueabihf 를 설치하세요." >&2
    exit 1
fi

# 램디스크는 통째로 RAM 에 적재되므로 디버그 정보를 남기지 않는다.
"$CC" -static -O2 -Wall -Wextra -s -o "$OUT" "$SRC_DIR/init_wrapper.c"

echo "==> 생성: $OUT"
file "$OUT" || true

# 정적 링크가 실제로 됐는지 확인한다. 동적 링크로 나오면 rdinit 시점에 실행되지 않는다.
if command -v readelf >/dev/null 2>&1; then
    if readelf -d "$OUT" 2>/dev/null | grep -q NEEDED; then
        echo "경고: 동적 링크되었습니다. rdinit 시점에는 실행되지 않습니다." >&2
        exit 1
    fi
    echo "==> 정적 링크 확인됨"
fi
