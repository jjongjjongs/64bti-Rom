#!/usr/bin/env bash
# Android 7 게스트용 ARM32 커널을 -machine virt 에서 부팅 가능하게 빌드한다.
#
# 사용법: build-kernel.sh <출력 zImage 경로>
#
# 이 파일이 워크플로에서 분리돼 있는 이유는 캐시 키 때문이다. 커널 빌드는 15분쯤
# 걸리는데, 워크플로 파일 전체를 해시로 삼으면 판정 단계 문구 하나만 고쳐도 캐시가
# 날아간다. 커널 결과물을 실제로 바꾸는 것은 이 스크립트뿐이므로 키도 이 파일만 본다.
#
# 커널 버전 고정 이유: ashmem 이 Linux 5.18 에서 제거됐다. Android 7 은 ashmem 없이는
# 부팅하지 못하므로 5.17 이하가 필요하고, 안드로이드 패치가 들어간 계보 중 가장 최신인
# android12-5.10-lts 를 쓴다.
set -euo pipefail

OUT="${1:?출력 경로를 인자로 넘겨야 한다}"
KERNEL_BRANCH="${KERNEL_BRANCH:-android12-5.10-lts}"
SRC="${KERNEL_SRC_DIR:-kernel-src}"

sudo apt-get install -y -qq gcc-arm-linux-gnueabihf bc bison flex libssl-dev

git clone --depth 1 -b "$KERNEL_BRANCH" \
  https://android.googlesource.com/kernel/common "$SRC"
cd "$SRC"

export ARCH=arm CROSS_COMPILE=arm-linux-gnueabihf-
make -s multi_v7_defconfig

# 안드로이드가 요구하는 것 + 우리 가상 하드웨어. 실측에서 나온 목록 그대로.
#
# LSM 을 명시하는 이유: SECURITY_SELINUX 를 켜는 것만으로는 부족하다. CONFIG_LSM 이
# 활성 LSM 목록을 정하고, 거기 selinux 가 없으면 selinuxfs 가 등록되지 않아 init 이
# 정책을 올리지 못한다.
#
# ANDROID_BINDERFS 를 끄는 이유: 켜져 있으면 binderfs 가 ANDROID_BINDER_DEVICES 의
# 이름들을 가져가서, 마운트된 binderfs 안에만 장치를 만든다. 그러면 legacy misc 장치가
# 등록되지 않는다(실측: /proc/filesystems 에는 binder 가 있는데 misc 클래스에는 ashmem
# 만 있고 binder 가 없었다). Android 7 은 binderfs 를 모르고 /dev/binder 를 직접 여는데,
# 그게 없으니 servicemanager 가 반복해서 죽고 init 이 recovery 로 리부트했다.
#
# SCHED_TUNE 은 여기 없다. 5.10 에서 제거됐고(uclamp 로 대체) --enable 해봐야 조용히
# 무시된다. init 이 /sys/fs/cgroup/stune 쓰기에 실패하며 로그를 남기지만 치명적이지 않다.
./scripts/config \
  --enable SECURITY --enable SECURITY_SELINUX --enable SECURITY_SELINUX_BOOTPARAM \
  --enable SECURITY_NETWORK --enable AUDIT \
  --disable SECURITY_APPARMOR \
  --set-str LSM "lockdown,yama,loadpin,safesetid,integrity,selinux" \
  --enable ANDROID --enable ANDROID_BINDER_IPC \
  --disable ANDROID_BINDERFS \
  --set-str ANDROID_BINDER_DEVICES "binder,hwbinder,vndbinder" \
  --enable ASHMEM \
  --enable POWER_SUPPLY --enable TEST_POWER \
  --enable PM_WAKELOCKS --enable PM_SLEEP --enable SUSPEND \
  --enable VIRTIO --enable VIRTIO_MMIO --enable VIRTIO_BLK \
  --enable VIRTIO_CONSOLE --enable VIRTIO_NET \
  --enable SERIAL_AMBA_PL011 --enable SERIAL_AMBA_PL011_CONSOLE \
  --enable SERIAL_EARLYCON --enable BLK_DEV_INITRD \
  --enable DEVTMPFS --enable DEVTMPFS_MOUNT \
  --enable EXT4_FS --enable EXT4_USE_FOR_EXT2 \
  --enable EXT4_FS_SECURITY --enable EXT4_FS_POSIX_ACL \
  --enable CGROUPS --enable CGROUP_SCHED --enable FAIR_GROUP_SCHED \
  --enable CGROUP_CPUACCT --enable MEMCG --enable CGROUP_FREEZER \
  --enable CGROUP_DEVICE --enable CPUSETS \
  --enable KALLSYMS --enable KALLSYMS_ALL \
  --disable MODULES
make -s olddefconfig

# 내장인지 확인한다. 모듈이면 램디스크에 모듈이 없어 아무 소용이 없다.
# 결과를 파일로도 남긴다. 이 단계는 캐시 적중이면 통째로 건너뛰므로, 판정 단계에서
# "지금 쓰는 커널이 어떤 설정으로 빌드됐는지"를 볼 방법이 이것뿐이다.
for opt in CONFIG_VIRTIO_CONSOLE CONFIG_SECURITY_SELINUX CONFIG_ANDROID_BINDER_IPC \
           CONFIG_ANDROID_BINDERFS CONFIG_ANDROID_BINDER_DEVICES \
           CONFIG_ASHMEM CONFIG_LSM CONFIG_KALLSYMS; do
  printf '%-34s %s\n' "$opt" "$(grep -E "^$opt=" .config || echo '(설정 안 됨)')"
done | tee "$(dirname "$OUT")/kernel-config.txt"

make -j"$(nproc)" zImage
cp arch/arm/boot/zImage "$OUT"
file "$OUT"
