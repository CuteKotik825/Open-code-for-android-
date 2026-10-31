#!/bin/bash
ANDROID_HOME=${ANDROID_HOME:-/usr/local/lib/android/sdk}
git clone --depth 1 https://github.com/green-green-avk/build-proot-android b
cd b
sed -i "s|^NDK=.*|NDK=\"$ANDROID_HOME/ndk/23.2.8568313\"|; s|^ARCHS=.*|ARCHS='aarch64 armv7a'|; s|else API=21|else API=\${PROOT_API:-21}|" config
grep -n "API=\|NDK=\|ARCHS=" config
./get-talloc.sh
./get-proot.sh
./make-talloc-static.sh
sed -i 's|-Werror=implicit-function-declaration"|-Werror=implicit-function-declaration -D_GNU_SOURCE"|' make-proot-for-apk.sh
grep -n "_GNU_SOURCE" make-proot-for-apk.sh
PROOT_API=24 ./make-proot-for-apk.sh
mkdir -p ../out/arm64-v8a ../out/armeabi-v7a
cp build/root-aarch64/root-apk/bin/libproot.so build/root-aarch64/root-apk/bin/libproot-loader.so build/root-aarch64/root-apk/bin/libproot-loader32.so ../out/arm64-v8a/ || ls build/root-aarch64/root-apk/bin
cp build/root-armv7a/root-apk/bin/libproot.so build/root-armv7a/root-apk/bin/libproot-loader.so ../out/armeabi-v7a/ || ls build/root-armv7a/root-apk/bin
ls -la ../out/*
(strings ../out/arm64-v8a/libproot.so | grep -i "process_vm" | head -3) || true
../out/arm64-v8a/libproot.so -V 2>/dev/null | head -20 || true
