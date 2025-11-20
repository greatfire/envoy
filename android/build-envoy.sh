#!/bin/bash
# https://medium.com/@marc.calandro/how-to-setup-gitlab-ci-for-your-android-projects-d4c48429366f
# https://about.gitlab.com/blog/2018/10/24/setting-up-gitlab-ci-for-android-projects/

cd $(dirname $0)

BUILD_ARGS=""

export ANDROID_COMPILE_SDK=35
export ANDROID_BUILD_TOOLS=35.0.0
export NDK_VERSION="27.0.12077973"
export ANDROID_CMDLINE_TOOLS="13114758"
export CMAKE_VERSION="3.10.2.4988404"

export ANDROID_HOME=${ANDROID_HOME:-$PWD/android-sdk-linux}
export CMAKE_HOME=$ANDROID_HOME/cmake/${CMAKE_VERSION}/bin
export PATH=$PATH:$ANDROID_HOME/platform-tools:$CMAKE_HOME
export sdkmanager=$ANDROID_HOME/cmdline-tools/bin/sdkmanager

set -euo pipefail
if [ ! -e $sdkmanager ]; then
    wget --continue --quiet --output-document=android-cmdline-tools.zip \
	 https://dl.google.com/android/repository/commandlinetools-linux-${ANDROID_CMDLINE_TOOLS}_latest.zip
    unzip -d $ANDROID_HOME android-cmdline-tools.zip
    rm -f android-cmdline-tools.zip
fi
# $sdkmanager --list|grep -i ndk
$sdkmanager --sdk_root=${ANDROID_HOME} "platforms;android-${ANDROID_COMPILE_SDK}"
$sdkmanager --sdk_root=${ANDROID_HOME} "platform-tools"
$sdkmanager --sdk_root=${ANDROID_HOME} "build-tools;${ANDROID_BUILD_TOOLS}"
$sdkmanager --sdk_root=${ANDROID_HOME} "ndk;${NDK_VERSION}"
$sdkmanager --sdk_root=${ANDROID_HOME} "cmake;${CMAKE_VERSION}"

set +o pipefail # sdkmanager --licenses "fails" if all licenses are already accepted
yes | $sdkmanager  --sdk_root=${ANDROID_HOME} --sdk_root=${ANDROID_HOME} --licenses
set -o pipefail

BUILD=${1:-release}
cp "../native/cronet-$BUILD.aar" ./envoy/cronet/
cp "../native/cronet-$BUILD.aar" ./demo/cronet/
if [[ $BUILD == "debug" ]]; then
    ./gradlew assembleDebug $BUILD_ARGS
else
    ./gradlew assembleRelease $BUILD_ARGS
fi
