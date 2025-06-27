#!/bin/bash
# https://chromium-review.googlesource.com/c/chromium/src/+/1044349
# https://chromium-review.googlesource.com/q/hashtag:%2522cronet%2522+(status:open+OR+status:merged)+maven

# https://bugs.chromium.org/p/chromium/issues/detail?id=506230
# https://chromium.googlesource.com/chromium/src.git/+/648e9a6566e497a173b1a0b2fbf76c0e829163da^!/#F3
# https://chromium.googlesource.com/chromium/src/+/0803a8bb25489af4db4a54542663f9fad87f2697^!/
# https://chromium.googlesource.com/chromium/src/+/c84b9756d482be2561734a19138b41ca630f2701

# https://github.com/GoogleChromeLabs/cronet-sample/commit/bf3068b73054947c3cbb035915cc0756dfb44d97
# https://chromium.googlesource.com/chromium/src/+/master/components/cronet/android/sample/README
# https://console.cloud.google.com/storage/browser/chromium-cronet/android/81.0.4039.0/Release/cronet

set -euo pipefail

CHROMIUM_SRC_ROOT=${CHROMIUM_SRC_ROOT:-/root/chromium/src}
DEPOT_TOOLS_ROOT=${DEPOT_TOOLS_ROOT:-/root/depot_tools}
CRONET_OUTPUT_DIR=${CRONET_OUTPUT_DIR:-$CHROMIUM_SRC_ROOT/out/Cronet/cronet}
BUILD_VARIANT=${1:-release}
source $CHROMIUM_SRC_ROOT/chrome/VERSION
CHROME_VERSION=$MAJOR.$MINOR.$BUILD.$PATCH
export PATH="$DEPOT_TOOLS_ROOT/bin:$PATH"

build_tmp_dir="$(mktemp --tmpdir=/tmp --directory -t cronet-build-"$(date +%Y-%m-%d)"-XXXXXXXXXX)"

mkdir -p "$build_tmp_dir"
cd "$build_tmp_dir" || exit 1
cp "$CRONET_OUTPUT_DIR/cronet_api.jar" classes.jar
sudo apt install --yes zipmerge
for file in cronet_impl_common_java.jar cronet_impl_native_java.jar cronet_impl_platform_java.jar cronet_shared_java.jar httpengine_native_provider_java.jar; do
    zipmerge classes.jar "$CRONET_OUTPUT_DIR/$file"
done
mkdir -p jni
rsync -avzp --include=libcronet.$CHROME_VERSION.so "$CRONET_OUTPUT_DIR/libs/" jni/
cat "$CRONET_OUTPUT_DIR"/{cronet_impl_common_proguard.cfg,cronet_impl_native_proguard.cfg,cronet_impl_platform_proguard.cfg,cronet_shared_proguard.cfg,httpengine_native_provider_proguard.cfg} >proguard.txt
touch R.txt
touch public.txt
# no heading empty line:
# Caused by: org.xml.sax.SAXParseException: 不允许有匹配 "[xX][mM][lL]" 的处理指令目标。
# https://weixiao.blog.csdn.net/article/details/81001749
echo '<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="org.chromium.net">
    <uses-sdk android:minSdkVersion="21" android:targetSdkVersion="24" />
    <uses-permission android:name="android.permission.INTERNET"/>
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE"/>
</manifest>' >AndroidManifest.xml
cd - || exit 2
jar cvMf "cronet-$BUILD_VARIANT.aar" -C $build_tmp_dir/ .

# updated Shadowsocks based on research:
# https://www.opentech.fund/news/exposing-the-great-firewalls-dynamic-blocking-of-fully-encrypted-traffic/
wget --continue https://github.com/gfw-report/shadowsocks-rust/releases/download/v0.0.1-beta/mobile-universal-release-signed.apk
unzip -o mobile-universal-release-signed.apk lib/*/libsslocal.so
rsync -avzp lib/ jni/
jar uvMf "cronet-$BUILD_VARIANT.aar" jni

