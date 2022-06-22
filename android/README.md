
## Download

Download cronet-debug.aar and cronet-release.aar [here](https://github.com/stevenmcdonald/envoy/releases/tag/102.0.5005.41-beta3).  Corresponding .aar files for Envoy can be found there if there are no local changes that need to be included.

## Build

Copy `cronet-$BUILD.aar`(debug and release) to `cronet/`, then run `./gradlew assembleDebug` or `./gradlew assembleRelease` to build the project.

## Get Started

Envoy has only one more extra API call than Google's [chromium](https://chromium.googlesource.com/chromium/src/+/master/components/cronet/)/android [cronet library](https://developer.android.com/guide/topics/connectivity/cronet): `CronetEngine.Builder.setEnvoyUrl` .

Build the demo module to see it in action, or just call this in `Activity`'s `onCreate` method:

```java
CronetNetworking.initializeCronetEngine(getApplicationContext(), "YOUR-ENVOY-URL"); // set envoy url here, read native/README.md for all supported formats.
```

## Shadowsocks Service

You can start the optional Shadowsocks service(ss-local) when the above envoy url is in socks5 protocol such as `socks5://127.0.0.1:1080`.
```java
String ssUri = "ss://Y2hhY2hhMjAtaWV0Zi1wb2x5MTMwNTpwYXNz@127.0.0.1:1234"; // your ss server connection url
Intent shadowsocksIntent = new Intent(this, ShadowsocksService.class);
shadowsocksIntent.putExtra("org.greatfire.envoy.START_SS_LOCAL", ssUri);
ContextCompat.startForegroundService(getApplicationContext(), shadowsocksIntent);
```

You can customize the local listen address/port.  The default values 127.0.0.1 and 1080 will be used if no values are provided.
```java
shadowsocksIntent.putExtra("org.greatfire.envoy.START_SS_LOCAL.LOCAL_ADDRESS", "127.0.0.2"); // socks5 host(also host for envoy url)
shadowsocksIntent.putExtra("org.greatfire.envoy.START_SS_LOCAL.LOCAL_PORT", 1081); // socks5 port(also port for envoy url)
```

You can set up a broadcast receiver and check for the intent action `ShadowsocksService.SHADOWSOCKS_SERVICE_BROADCAST`.  If the intent action matches, check the integer extra `ShadowsocksService.SHADOWSOCKS_SERVICE_RESULT`.  If the extra is greater than zero, it indicates that the Shadowsocks service was started successfully.

## Multiple envoy urls

```java
@Override
protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    // register to receive results
    LocalBroadcastManager.getInstance(this).registerReceiver(mBroadcastReceiver, IntentFilter().apply {
        addAction(BROADCAST_URL_VALIDATION_SUCCEEDED)
        addAction(BROADCAST_URL_VALIDATION_FAILED)
        addAction(ShadowsocksService.SHADOWSOCKS_SERVICE_BROADCAST) // see shadowsocks service behavior above
    });

    List<String> envoyUrls = Collections.unmodifiableList(Arrays.asList("https://allowed.example.com/path/", "socks5://127.0.0.1:1080"));
    NetworkIntentService.submit(this, envoyUrls);

    // or call NetworkIntentService.enqueueQuery, and
    // we will get responses in Receiver's onReceive
    // NetworkIntentService.enqueueQuery(this);
    ...
}

protected final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent != null) {
            if (intent.action == BROADCAST_URL_VALIDATION_SUCCEEDED) {
                final List<String> validUrls = intent.getStringArrayListExtra(org.greatfire.envoy.NetworkIntentServiceKt..EXTENDED_DATA_VALID_URLS);
                Log.i("BroadcastReceiver", "Received valid urls: " + TextUtils.join(", ", validUrls));
                if (validUrls != null && !validUrls.isEmpty()) {
                    String envoyUrl = validUrls.get(0);
                    // the first listed url should be the fastest option
                    // this will be triggered multiple times as additional urls are validated
                    // consider adding code so that Cronet is only initialized once
                    CronetNetworking.initializeCronetEngine(context, envoyUrl); // reInitializeIfNeeded set to false
                }
            } else if (intent.action == BROADCAST_URL_VALIDATION_FAILED) {
                final List<String> invalidUrls = intent.getStringArrayListExtra(org.greatfire.envoy.NetworkIntentServiceKt..EXTENDED_DATA_INVALID_URLS);
                Log.i("BroadcastReceiver", "Received invalid urls: " + TextUtils.join(", ", validUrls));
            } else if (intent.action == ShadowsocksService.SHADOWSOCKS_SERVICE_BROADCAST) {
                final int shadowsocksResult = intent.getIntExtra(ShadowsocksService.SHADOWSOCKS_SERVICE_RESULT, 0);
                // check service result as described above
                // consider submitting urls only after the shadowsocks service has had a chance to start
            }
        }
    }
};
```

Add uses-permission and services to AndroidManifest.xml
```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
...

<service
    android:name="org.greatfire.envoy.ShadowsocksService"
    android:exported="false"
    android:isolatedProcess="false" />
<service
    android:name="org.greatfire.envoy.NetworkIntentService"
    android:exported="false" />
```

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
