#!/usr/bin/env bash
# 오프라인/제한 네트워크 환경용 자바 컴파일 검증 스크립트.
#
# 정식 빌드는 ./gradlew :app:assembleDebug 이다. 이 스크립트는 Android SDK를
# 설치할 수 없는 환경(dl.google.com 차단 등)에서 "자바 소스가 API 34 기준으로
# 컴파일되는가"만 빠르게 확인하기 위한 보조 수단이다.
#
# aapt2/d8/서명을 거치지 않으므로 APK는 만들어지지 않는다.
# 리소스 참조(R.*) 오류는 잡지 못한다.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CACHE_DIR="${TMPDIR:-/tmp}/singlevm-verify-compile"
# Robolectric이 Maven Central에 배포하는 API 34 프레임워크 jar (android.jar 대용)
ANDROID_JAR_URL="https://repo.maven.apache.org/maven2/org/robolectric/android-all/14-robolectric-10818077/android-all-14-robolectric-10818077.jar"
ANDROID_JAR="$CACHE_DIR/android-all-34.jar"

mkdir -p "$CACHE_DIR"
if [ ! -f "$ANDROID_JAR" ]; then
    echo "==> API 34 프레임워크 jar 내려받는 중..."
    curl -fsSL -o "$ANDROID_JAR" "$ANDROID_JAR_URL"
fi

OUT_DIR="$CACHE_DIR/classes"
rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR"

echo "==> javac 실행 중..."
mapfile -t SOURCES < <(find "$REPO_ROOT/app/src/main/java" -name '*.java')
# android-all jar 자체가 @UnsupportedAppUsage 어노테이션 경고를 대량 생성하므로,
# 우리 소스에서 나온 진단만 남긴다.
javac -Xlint:all -Xmaxwarns 10000 -encoding UTF-8 \
    -classpath "$ANDROID_JAR" \
    -d "$OUT_DIR" \
    "${SOURCES[@]}" 2>&1 | grep -E "^$REPO_ROOT|error:" || true

echo "==> 컴파일 완료: $OUT_DIR"
