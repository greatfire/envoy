
## Download

- [cronet-release.aar](https://envoy.greatfire.org/static/cronet-release.aar)
- [cronet-debug.aar](https://envoy.greatfire.org/static/cronet-debug.aar)

## Build

Copy `cronet-$BUILD.aar`(debug and release) to `cronet/`, then run `./gradlew assembleDebug` or `./gradlew assemble` to build the project.

## Get Started

Envoy has only one more extra API call than Google's [chromium](https://chromium.googlesource.com/chromium/src/+/master/components/cronet/)/android [cronet library](https://developer.android.com/guide/topics/connectivity/cronet): `CronetEngine.Builder.setEnvoyUrl` .

Build the demo module to see it in action, or just call this in `Activity`'s `onCreate` method:

```java
CronetNetworking.initializeCronetEngine(getApplicationContext(), "YOUR-ENVOY-URL"); // set envoy url here, read native/README.md for all supported formats.
```

## Shadowsocks Service

You can start the optional Shadowsocks Service(ss-local) when the above envoy url is in socks5 protocol such as `socks5://127.0.0.1:1080`.
```java
String ssUri = "ss://Y2hhY2hhMjAtaWV0Zi1wb2x5MTMwNTpwYXNz@127.0.0.1:1234"; // your ss server connection url
Intent shadowsocksIntent = new Intent(this, ShadowsocksService.class);
shadowsocksIntent.putExtra("org.greatfire.envoy.START_SS_LOCAL", ssUri);
ContextCompat.startForegroundService(getApplicationContext(), shadowsocksIntent);
```

And you can even customize the local listen address/port:
```
shadowsocksIntent.putExtra("org.greatfire.envoy.START_SS_LOCAL.LOCAL_ADDRESS", "127.0.0.1"); // socks5 host(also host for envoy url)
shadowsocksIntent.putExtra("org.greatfire.envoy.START_SS_LOCAL.LOCAL_PORT", 1080); // socks5 port(also port for envoy url)
```

You can receive `com.greatfire.envoy.SS_LOCAL_STARTED` broadcast with  
 `com.greatfire.envoy.SS_LOCAL_STARTED.LOCAL_ADDRESS` and `com.greatfire.envoy.SS_LOCAL_STARTED.LOCAL_PORT` as extras when ss services is started.

## FAQ

### library strip error
```
Task :app:stripDevDebugDebugSymbols
/Users/xxx/Library/Android/sdk/ndk-bundle/toolchains/arm-linux-androideabi-4.9/prebuilt/darwin-x86_64/bin/arm-linux-androideabi-strip:/Users/me/Downloads/apps-android-wikipedia/app/build/intermediates/merged_native_libs/devDebug/out/lib/armeabi-v7a/libcronet.72.0.3626.122.so: File format not recognized

Unable to strip library /Users/xxx/apps-android-wikipedia/app/build/intermediates/merged_native_libs/devDebug/out/lib/armeabi-v7a/libcronet.72.0.3626.122.so due to error 1 returned from /Users/me/Library/Android/sdk/ndk-bundle/toolchains/arm-linux-androideabi-4.9/prebuilt/darwin-x86_64/bin/arm-linux-androideabi-strip, packaging it as is
```

configure module build.gradle:
```
defaultConfig {
    packagingOptions {
        doNotStrip '**/libcronet*.so'
    }
}
```

### merged aar files
- [gradle - Android Studio how to package single AAR from multiple library projects?](https://stackoverflow.com/questions/20700581/android-studio-how-to-package-single-aar-from-multiple-library-projects/20715155#20715155)
- [Android native library merging](https://engineering.fb.com/android/android-native-library-merging/)

## TODO
1. customCronetBuilder in CronetNetworking
2. [WebView 内容远程调试](https://hearrain.com/webview-remote-debugging)

## Thanks and Acknowledgements
1. [react-native-cronet](https://github.com/akshetpandey/react-native-cronet).
2. [cronet](https://github.com/lizhangqu/cronet)
2. [How to choose an Android HTTP Library](https://appdevelopermagazine.com/how-to-choose-an-android-http-library/)
