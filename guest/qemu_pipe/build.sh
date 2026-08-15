#!/usr/bin/env bash
# 게스트측 goldfish pipe 도구 빌드 — 정적 링크 ARM32
#
#   qemu_piped : /dev/qemu_pipe 를 CUSE 로 제공하는 데몬
#   pipetest   : 동시 파이프가 실제로 되는지 확인하는 시험 도구
#
# init.wrapper 와 같은 이유로 정적이어야 한다: 램디스크에서 실행되고, 그 시점에는
# /system 이 없어 동적 링커도 libc.so 도 기대할 수 없다.
#
# 사용법:
#   ANDROID_NDK_HOME=~/Android/Sdk/ndk/27.0.12077973 ./build.sh [출력디렉터리]
set -euo pipefail

SRC_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUTDIR="${1:-$SRC_DIR}"
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

build() {   # $1 = 소스, $2 = 출력 이름
    local out="$OUTDIR/$2"
    "$CC" -static -O2 -Wall -Wextra -s -pthread -o "$out" "$SRC_DIR/$1"
    echo "==> 생성: $out"
    file "$out" || true
    if command -v readelf >/dev/null 2>&1; then
        if readelf -d "$out" 2>/dev/null | grep -q NEEDED; then
            echo "경고: $2 가 동적 링크되었습니다. 램디스크에서 실행되지 않습니다." >&2
            exit 1
        fi
    fi
}

build qemu_piped.c qemu_piped
build pipetest.c   pipetest
echo "==> 정적 링크 확인됨"
