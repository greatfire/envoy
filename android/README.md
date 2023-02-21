
## Download

Cronet and IEnvoyProxy dependencies are now provided by Maven and no additional .aar files need to be downloaded.

## Server setup

Some documentation and example Ansible playbooks are available [here](https://gitlab.com/stevenmcdonald/envoy-proxy-examples/). The values used below will be based on what's used when configuring the server.

## Build

Run `./build-envoy.sh debug` or `./build-envoy.sh release` to build an envoy .aar file that can be indluded in other projects.

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
 - hysteria://ip:port?obfs=...

V2Ray Websocket:
 - v2ws://domain:port?path=...&id=...

V2Ray SRTP:
 - v2srtp://ip:port?id=...

V2Ray WeChar:
 - v2wechat://ip:port?id=...

## DNSTT config format

Additional parameters are required for the optional DNSTT service:
- the domain name used for DNSTT
- the authentication key for DNSTT
- the path to the file on the DNSTT HTTP server that contains additional urls
- the URL or address of a reachable DNS over HTTP provider
- the URL or address of a reachable DNS over TCP provider

Only a DNS over HTTP or DNS over TCP provider is required, an empty string should be provided for the other
    
## Submit envoy urls
    
There are two options for submitting envoy urls:
    
 - submit(context: Context, urls: List<String>)
 - submit(context: Context, urls: List<String>, directUrls: List<String>?, hysteriaCert: String?, dnsttConfig: List<String>?)

The first method signature is intended for backwards compatibility, it will not support a Hysteria URL or fetch additional URLs with DNSTT.

If the optional directUrls parameter is included, Envoy will attempt to connect to those urls directly first. This can be included to avoid using proxy resources when the target domain is not blocked.

The optional hysteriaCert parameter must be included if you submit any Hysteria URLs. It is a comma delimited string representing a self generated root certificate for the hysteria server in PEM format.

If the optional dnsttConfig parameter is included, Envoy will attempt to fetch additional proxy URLs using DNSTT if all of the provided URLs fail.

## Envoy broadcasts

Envoy provides feedback with a variety of broadcasts. Create a BroadcastReceiver and add actions to an IntentFilter as needed. The following are some of the more significant actions and their parameters:

 - ENVOY_BROADCAST_VALIDATION_SUCCEEDED, includes ENVOY_DATA_URL_SUCCEEDED and ENVOY_DATA_SERVICE_SUCCEEDED
 
Received when a URL is validated successfully. This can include any direct URLs that were submitted. The parameters include the URL that was validated and the corresponding service. Use this URL to initialize Cronet (initializing Cronet with a direct URL will cause a redirection exception).
 
 - ENVOY_BROADCAST_VALIDATION_FAILED, includes includes ENVOY_DATA_URL_FAILED and ENVOY_DATA_SERVICE_FAILED
 
Received when a URL fails validation. This can include any direct URLs that were submitted. The parameters include the URL that failed validation and the corresponding service.
 
 - ENVOY_BROADCAST_VALIDATION_CONTINUED, includes ENVOY_DATA_URLS_CONTINUED
 
Received if all URL fail validation and additional URLs are fetched with DNSTT. The parameters include the list of additional URLs. This should not include any URLs that were already submitted.

## Basic envoy integration

```kotlin
    private val envoyBroadcastReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent != null && context != null) {
                if (intent.action == org.greatfire.envoy.ENVOY_BROADCAST_VALIDATION_SUCCEEDED) {
                    val validUrl = intent.getStringExtra(org.greatfire.envoy.ENVOY_DATA_URL_SUCCEEDED)                    
                    if (validUrl != null) {
                        CronetNetworking.initializeCronetEngine(context, validUrl)
                    } else {
                        // received null url
                    }
                } else if (intent.action == org.greatfire.envoy.ENVOY_BROADCAST_VALIDATION_FAILED) {
                    val invalidUrl = intent.getStringExtra(org.greatfire.envoy.ENVOY_DATA_URL_FAILED)
                    if (invalidUrl != null) {
                        // handle error state
                    } else {
                        // received null url
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

        LocalBroadcastManager.getInstance(this).registerReceiver(envoyBroadcastReceiver, IntentFilter().apply {
            addAction(org.greatfire.envoy.ENVOY_BROADCAST_VALIDATION_SUCCEEDED)
            addAction(org.greatfire.envoy.ENVOY_BROADCAST_VALIDATION_FAILED)
        })
    
        val listOfUrls = mutableListOf<String>()
        listOfUrls.add(urlOne)
        listOfUrls.add(urlTwo)
        val directUrls = mutableListOf<String>()
        directUrls.add(directUrl)
        /* expected format:
           0. dnstt domain
           1. dnstt key
           2. dnstt path
           3. doh url
           4. dot address
           (either 4 or 5 should be an empty string) */
        val dnsttConfig = mutableListOf<String>()
        dnsttConfig.add(dnsttDomain)
        dnsttConfig.add(dnsttKey)
        dnsttConfig.add(dnsttPath)
        dnsttConfig.add(dohUrl)
        dnsttConfig.add("")
        org.greatfire.envoy.NetworkIntentService.submit(this@MainActivity, listOfUrls, directUrls, hysteriaCert, dnsttConfig)
    }
```

Elsewhere, add the CronetInterceptor to your network client. This interceptor will be automatically bypassed if CronetEngine has not been initialized.

```kotlin
    private fun createClient(): OkHttpClient {
        return OkHttpClient.Builder()
                .addInterceptor(CronetInterceptor())
                .build()
    }
```

Add uses-permission and services to AndroidManifest.xml
```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
...

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
