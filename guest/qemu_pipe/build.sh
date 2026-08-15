#!/usr/bin/env bash
# qemu_pipe_link 빌드 — 정적 링크 ARM32 실행 파일
#
# init.wrapper 와 같은 이유로 정적이어야 한다: 램디스크에서 실행되고, 그 시점에는
# /system 이 없어 동적 링커도 libc.so 도 기대할 수 없다.
#
# 사용법:
#   ANDROID_NDK_HOME=~/Android/Sdk/ndk/27.0.12077973 ./build.sh [출력경로]
set -euo pipefail

SRC_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="${1:-$SRC_DIR/qemu_pipe_link}"
API="${API_LEVEL:-24}"

if [ -n "${ANDROID_NDK_HOME:-}" ]; then
    CC="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/armv7a-linux-androideabi${API}-clang"
    [ -x "$CC" ] || { echo "NDK 컴파일러를 찾을 수 없습니다: $CC" >&2; exit 1; }
    echo "==> NDK(bionic) 로 빌드: API $API"
elif command -v arm-linux-gnueabihf-gcc >/dev/null 2>&1; then
    CC=arm-linux-gnueabihf-gcc
    echo "==> 폴백: arm-linux-gnueabihf-gcc (glibc). 검증용으로만 쓰세요."
else
    echo "ARM 컴파일러가 없습니다." >&2
    exit 1
fi

"$CC" -static -O2 -Wall -Wextra -s -o "$OUT" "$SRC_DIR/pipelink.c"
echo "==> 생성: $OUT"
file "$OUT" || true

if command -v readelf >/dev/null 2>&1; then
    if readelf -d "$OUT" 2>/dev/null | grep -q NEEDED; then
        echo "경고: 동적 링크되었습니다. 램디스크에서 실행되지 않습니다." >&2
        exit 1
    fi
    echo "==> 정적 링크 확인됨"
fi
