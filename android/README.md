
## Download

Download cronet-debug.aar and cronet-release.aar [here](https://github.com/stevenmcdonald/envoy/releases/tag/102.0.5005.41-4). Download the latest version of IEnvoyProxy.aar [here](https://github.com/stevenmcdonald/IEnvoyProxy/releases) or use the existing maven dependency.

## Build

Copy `cronet-$BUILD.aar`(debug and release) to `cronet/`, then run `./gradlew assembleDebug` or `./gradlew assembleRelease` to build the project.

Additional parameters are required for the optional dnstt service:
 - -Pdnsttserver="..." (the hostname of a dnstt server)
 - -Pdnsttkey="..." (the authentication key for the dnstt server)
 - -Pdnsttpath="..." (the path to the file on the dnstt server that contains additional urls)
 - -PdohUrl="..." OR  -PdotAddr="..." (the url or address of a reachable dns provider)

Additional parameters are required for the optional hysteria service:
 - -Phystcert="..." (the key and certificate for the hysteria server in the form of a comma separated list of the key and each line of the certificate)
 
To use the build-envoy.sh script, include these values in a secrets.sh file in the android folder with the following format:

```java
export DNSTT_SERVER=...
export DNSTT_KEY=...
export DNSTT_PATH=...
export DNSTT_DOH_URL=... (OR export DNSTT_DOT_ADDR=...)
export HYSTERIA_CERT=...
```

## Get Started

Envoy has only one more extra API call than Google's [chromium](https://chromium.googlesource.com/chromium/src/+/master/components/cronet/)/android [cronet library](https://developer.android.com/guide/topics/connectivity/cronet): `CronetEngine.Builder.setEnvoyUrl` .

Build the demo module to see it in action, or just call this in `Activity`'s `onCreate` method:

```java
CronetNetworking.initializeCronetEngine(getApplicationContext(), "YOUR-ENVOY-URL"); // set envoy url here, read native/README.md for all supported formats.
```
## Envoy url format

Http/Https:
 - http://domain:port (no trailing slash)
 - https://domain:port (no trailing slash)

Shadowsocks:
 - ss://encrypted login@ip:port/

Hysteria:
 - hysteria://ip:port

V2Ray Websocket:
 - v2ws://domain:port:/id:uuid

V2Ray SRTP:
 - v2srtp://ip:port:uuid

V2Ray WeChar:
 - v2wechat://ip:port:uuid
    
## Submit envoy urls
    
There are two options for submitting envoy urls:
    
 - submit(context: Context, urls: List<String>)
 - submit(context: Context, urls: List<String>, directUrl: String)
    
If the optional directUrl parameter is included, Envoy will attempt to connect to that url directly first.  This can be included to avoid using proxy resources when the target domain is not blocked.

## Basic envoy integration

```kotlin
    private val mBroadcastReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent != null && context != null) {
                if (intent.action == org.greatfire.envoy.BROADCAST_URL_VALIDATION_SUCCEEDED) {
                    val validUrls = intent.getStringArrayListExtra(org.greatfire.envoy.EXTENDED_DATA_VALID_URLS)
                    if (validUrls != null && !validUrls.isEmpty()) {
                        val envoyUrl = validUrls[0]
                        CronetNetworking.initializeCronetEngine(context, envoyUrl)
                    } else {
                        // received empty list
                    }
                } else if (intent.action == org.greatfire.envoy.BROADCAST_URL_VALIDATION_FAILED) {
                    val invalidUrls = intent.getStringArrayListExtra(org.greatfire.envoy.EXTENDED_DATA_INVALID_URLS)
                    if (invalidUrls != null && !invalidUrls.isEmpty()) {
                        // handle error state
                    } else {
                        // received empty list
                    }
                } else {
                    // received unexpected intent
                }
            } else {
                // received null intent or context
            }
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        LocalBroadcastManager.getInstance(this).registerReceiver(mBroadcastReceiver, IntentFilter().apply {
            addAction(org.greatfire.envoy.BROADCAST_URL_VALIDATION_SUCCEEDED)
            addAction(org.greatfire.envoy.BROADCAST_URL_VALIDATION_FAILED)
        })
    
        val listOfUrls = mutableListOf<String>()
        listOfUrls.add(foo)
        listOfUrls.add(bar)
        org.greatfire.envoy.NetworkIntentService.submit(this@MainActivity, listOfUrls)
    }
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
