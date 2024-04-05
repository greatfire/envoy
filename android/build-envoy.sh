#!/bin/bash
# https://medium.com/@marc.calandro/how-to-setup-gitlab-ci-for-your-android-projects-d4c48429366f
# https://about.gitlab.com/blog/2018/10/24/setting-up-gitlab-ci-for-android-projects/

cd $(dirname $0)

BUILD_ARGS=""

system_type="$(uname -s | tr '[:upper:]' '[:lower:]')"
if [ "$system_type" = "darwin" ]; then
  clt_type="mac"
else
  clt_type="linux"
fi

export ANDROID_COMPILE_SDK=33
export ANDROID_BUILD_TOOLS=30.0.2
export ANDROID_SDK_TOOLS=11076708
# https://github.com/gradle/gradle/issues/12440#issuecomment-606188282
export NDK_VERSION="26.2.11394342"
export CMAKE_VERSION="3.22.1"

export ANDROID_SDK_ROOT=${ANDROID_SDK_ROOT:-$PWD/android-sdk-${system_type}}
export CMAKE_HOME=$ANDROID_SDK_ROOT/cmake/${CMAKE_VERSION}/bin
export PATH=$PATH:$ANDROID_SDK_ROOT/platform-tools:$CMAKE_HOME
export sdkmanager=$ANDROID_SDK_ROOT/cmdline-tools/bin/sdkmanager

set -euo pipefail
if [ ! -e $sdkmanager ]; then
    wget --continue --quiet --output-document=android-sdk.zip \
    https://dl.google.com/android/repository/commandlinetools-${clt_type}-${ANDROID_SDK_TOOLS}_latest.zip
    unzip -d $ANDROID_SDK_ROOT android-sdk.zip
    rm -f android-sdk.zip
fi
# $sdkmanager --list|grep -i ndk
echo y | $sdkmanager --sdk_root=$ANDROID_SDK_ROOT "platforms;android-${ANDROID_COMPILE_SDK}"
echo y | $sdkmanager --sdk_root=$ANDROID_SDK_ROOT "platform-tools"
echo y | $sdkmanager --sdk_root=$ANDROID_SDK_ROOT "build-tools;${ANDROID_BUILD_TOOLS}"
echo y | $sdkmanager --sdk_root=$ANDROID_SDK_ROOT "ndk;${NDK_VERSION}"
echo y | $sdkmanager --sdk_root=$ANDROID_SDK_ROOT "cmake;${CMAKE_VERSION}"

set +o pipefail # sdkmanager --licenses "fails" if all licenses are already accepted
yes | $sdkmanager --sdk_root=$ANDROID_SDK_ROOT --licenses
set -o pipefail

BUILD=${1:-release}
cp "../native/cronet-$BUILD.aar" ./envoy/cronet/
cp "../native/cronet-$BUILD.aar" ./demo/cronet/
if [[ $BUILD == "debug" ]]; then
    ./gradlew assembleDebug $BUILD_ARGS
else
    ./gradlew assembleRelease $BUILD_ARGS
fi
