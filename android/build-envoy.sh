#!/bin/bash
# https://medium.com/@marc.calandro/how-to-setup-gitlab-ci-for-your-android-projects-d4c48429366f
# https://about.gitlab.com/blog/2018/10/24/setting-up-gitlab-ci-for-android-projects/

cd $(dirname $0)

BUILD_ARGS=""

export ANDROID_COMPILE_SDK=29
export ANDROID_BUILD_TOOLS=30.0.2
export ANDROID_SDK_TOOLS=4333796
# https://github.com/gradle/gradle/issues/12440#issuecomment-606188282
export NDK_VERSION="21.0.6113669"
export CMAKE_VERSION="3.10.2.4988404"

export ANDROID_SDK_ROOT=${ANDROID_SDK_ROOT:-$PWD/android-sdk-linux}
export CMAKE_HOME=$ANDROID_SDK_ROOT/cmake/${CMAKE_VERSION}/bin
export PATH=$PATH:$ANDROID_SDK_ROOT/platform-tools:$CMAKE_HOME
export sdkmanager=$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager

set -euo pipefail
if [ ! -e $sdkmanager ]; then
    wget --continue --quiet --output-document=android-sdk.zip \
	 https://dl.google.com/android/repository/sdk-tools-linux-${ANDROID_SDK_TOOLS}.zip
    unzip -d $ANDROID_SDK_ROOT android-sdk.zip
    rm -f android-sdk.zip
fi
# $sdkmanager --list|grep -i ndk
echo y | $sdkmanager "platforms;android-${ANDROID_COMPILE_SDK}"
echo y | $sdkmanager "platform-tools"
echo y | $sdkmanager "build-tools;${ANDROID_BUILD_TOOLS}"
echo y | $sdkmanager "ndk;${NDK_VERSION}"
echo y | $sdkmanager "cmake;${CMAKE_VERSION}"

set +o pipefail # sdkmanager --licenses "fails" if all licenses are already accepted
yes | $sdkmanager --licenses
set -o pipefail

BUILD=${1:-release}
cp "../native/cronet-$BUILD.aar" ./envoy/cronet/
cp "../native/cronet-$BUILD.aar" ./demo/cronet/
if [[ $BUILD == "debug" ]]; then
    ./gradlew assembleDebug $BUILD_ARGS
else
    # lint checks debug dependencies for release builds
    cp "../native/cronet-debug.aar" ./envoy/cronet/
    cp "../native/cronet-debug.aar" ./demo/cronet/
    ./gradlew assembleRelease $BUILD_ARGS
fi
