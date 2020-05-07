#!/bin/bash
# https://medium.com/@marc.calandro/how-to-setup-gitlab-ci-for-your-android-projects-d4c48429366f
# https://about.gitlab.com/blog/2018/10/24/setting-up-gitlab-ci-for-android-projects/

export ANDROID_COMPILE_SDK=29
export ANDROID_BUILD_TOOLS=29.0.2
export ANDROID_SDK_TOOLS=4333796
# https://github.com/gradle/gradle/issues/12440#issuecomment-606188282
export NDK_VERSION="21.0.6113669"
export CMAKE_VERSION="3.10.2.4988404"

export ANDROID_SDK_ROOT=$PWD/android-sdk-linux
export CMAKE_HOME=$PWD/android-sdk-linux/cmake/${CMAKE_VERSION}/bin/
export PATH=$PATH:$PWD/android-sdk-linux/platform-tools/:$CMAKE_HOME

set -e
wget --continue --quiet --output-document=android-sdk.zip https://dl.google.com/android/repository/sdk-tools-linux-${ANDROID_SDK_TOOLS}.zip
[[ ! -d android-sdk-linux ]] && unzip -d android-sdk-linux android-sdk.zip
# android-sdk-linux/tools/bin/sdkmanager --list|grep -i ndk
echo y | android-sdk-linux/tools/bin/sdkmanager "platforms;android-${ANDROID_COMPILE_SDK}"
echo y | android-sdk-linux/tools/bin/sdkmanager "platform-tools"
echo y | android-sdk-linux/tools/bin/sdkmanager "build-tools;${ANDROID_BUILD_TOOLS}"
echo y | android-sdk-linux/tools/bin/sdkmanager "ndk;${NDK_VERSION}"
echo y | android-sdk-linux/tools/bin/sdkmanager "cmake;${CMAKE_VERSION}"

yes | android-sdk-linux/tools/bin/sdkmanager --licenses

BUILD=${1:-release}
cp "../native/cronet-$BUILD.aar" ./cronet/
if [[ $BUILD == "debug" ]]; then
    ./gradlew assembleDebug
else
    ./gradlew assembleRelease
fi
