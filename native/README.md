
Download these aar files [cronet-release-v1.0.0.aar](https://envoy.greatfire.org/static/cronet-release.aar) or [cronet-debug-v1.0.0.aar](https://djy1j2o9tpazn.cloudfront.net/static/cronet-debug.aar).

### How to use
1. [Quick Start Guide to Using Cronet](https://chromium.googlesource.com/chromium/src/+/master/components/cronet/README.md), [native API](https://chromium.googlesource.com/chromium/src/+/master/components/cronet/native/test_instructions.md), [Android API](https://chromium.googlesource.com/chromium/src/+/master/components/cronet/android/test_instructions.md)
3. [Perform network operations using Cronet](https://developer.android.com/guide/topics/connectivity/cronet)
3. Samples with test: [native sample](https://chromium.googlesource.com/chromium/src/+/master/components/cronet/native/sample), [Android sample](https://chromium.googlesource.com/chromium/src/+/master/components/cronet/android/sample/README), [GoogleChromeLabs / cronet_sample](https://github.com/GoogleChromeLabs/cronet-sample/blob/master/android/app/src/main/java/com/google/samples/cronet_sample/ViewAdapter.java#L80)

The key point is `Cronet_EngineParams_envoy_url_set(engine_params, "ENVOY_URL")` for native 
or `cronetEngineBuilder.setEnvoyUrl("ENVOY_URL")` for android.

### Envoy URL

In one format of `https://DOMAIN/PATH` or `envoy://?k1=v1&k2=v2`

### Parameters

keys for `envoy://?k1=v1[&<more-pairs>...]` format:

* url: proxy URL, for example, https://allowed.example.com/path/
* header_xxx: HTTP header, header_Host=my-host` will send Host header with value my-host
* address: IP address for domain in proxy URL, to replace IP from DNS resolving
* resolve: resolve map, same as `--host-resolver-rules` command line for chromium, [Chromium docs](https://www.chromium.org/developers/design-documents/network-stack/socks-proxy), [lighthouse issue #2817](https://github.com/GoogleChrome/lighthouse/issues/2817), [firefox bug #1523367](https://bugzilla.mozilla.org/show_bug.cgi?id=1523367)
* disable_cipher_suites: cipher suites list to disable, same as `--cipher-suite-blacklist` command line, [chromium bug #58831](https://bugs.chromium.org/p/chromium/issues/detail?id=58831), [Firefox Support Forum](https://support.mozilla.org/en-US/questions/1119007#answer-867850)
* salt: a 16 characters long random string, unique to each combination of app-signing key, user, and device, such [ANDROID_ID](https://developer.android.com/reference/android/provider/Settings.Secure.html#ANDROID_ID).

### Examples

1. the simples form, a simple HTTP/HTTPS URL: `https://allowed.example.com/app1/`
2. set host:`envoy://?url=https%3A%2F%2Fexample.com%2Fapp1%2F%3Fk1%3Dv1&header_Host=forbidden.example.com`
3. only MAP url-host to address: `envoy://?url=https%3A%2F%2Fexample.com%2Fapp1%2F%3Fk1%3Dv1&header_Host=forbidden.example.com&address=1.2.3.4`
4. custom host override: `envoy://?url=https%3A%2F%2Fexample.com%2Fapp1%2F%3Fk1%3Dv1&header_Host=forbidden.example.com&resolve=MAP%20example.com%201.2.3.4,%20example2.com%201.2.3.5:443`
5. disable some cipher suites:  `envoy://?url=https%3A%2F%2Fallowed.example.com%2Fapp1%2F%3Fk1%3Dv1&header_Host=forbidden.example.com&address=1.2.3.4&disabled_cipher_suites=0xc024,0xc02f`

In example 5: allowed.example.com will be TLS SNI, forbidden.example.com will be Host HTTP header, 1.2.3.4 will be IP for allowed.example.com.

The equivalent curl command(see below for nginx conf):

`curl --resolve allowed.example.com:443:1.2.3.4 \
      --header 'Host: forbidden.example.com' \
      --header 'Url-Orig: https://forbidden.example.com' --header 'Host-Orig: forbidden.example.com' \
      https://allowed.example.com/app1/ # --ciphers ECDHE-RSA-AES128-GCM-SHA256 `

Note: _hash=HASH` will be appended to url in all cases for cache ~~invalidation~~.

## Setup
### Backend

Use Nginx as a`reverse proxy`

```
location ~ ^/app1/ {
    proxy_ssl_server_name on;
    proxy_pass $http_url_orig;
    proxy_buffering off; # disable buffer for stream
    proxy_set_header Host $http_host_orig;
    proxy_hide_header Host-Orig;
    proxy_hide_header Url-Orig;
    proxy_pass_request_headers on;
}
```

### Native
[sample main.cc](https://chromium.googlesource.com/chromium/src/+/master/components/cronet/native/sample/main.cc)

```bash
# ... after patching chromium source
export ENVOY_URL=https://example.com/path/
sed -i s#https://example.com/enovy_path/#$ENVOY_URL#g components/cronet/native/sample/main.cc
autoninja -C out/Cronet-Desktop cronet_sample cronet_sample_test
out/Cronet-Desktop/cronet_sample https://ifconfig.co/ip
```

### Android

[CronetSampleActivity.java](https://chromium.googlesource.com/chromium/src/+/master/components/cronet/android/sample/src/org/chromium/cronet_sample_apk/CronetSampleActivity.java)

```bash
# ... after patching chromium source
export ENVOY_URL=https://example.com/path/
sed -i s#https://example.com/enovy_path/#$ENVOY_URL#g components/cronet/android/sample/src/org/chromium/cronet_sample_apk/CronetSampleActivity.java
autoninja -C out/Cronet cronet_sample_apk
adb install out/Cronet/apks/CronetSample.apk
```
