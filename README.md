C and Java Library derived from chromium [cronet](https://chromium.googlesource.com/chromium/src/+/master/components/cronet/).

We are looking for developers to improve envoy together, please contact support@greatfire.org.

我们正在寻找开发者一同改进envoy，详情联系support@greatfire.org。

Technical details are explained [here](native/README.md).

* for pure c and java cronet library, see directory `native/`.
* for the android library, import directory `android/` from android studio or build with gradle from the command line.

Please note that this project is unrelated to the [Envoy Proxy project](https://www.envoyproxy.io/).

# ENVOY Developer Guide

Welcome to the envoy developer guide.
This document will teach you how to build Android apps using
APIs in the Android framework and other libraries.

## Protocols which Envoy supports

- HTTPS: proxy all traffic through regular web servers (or use Content Delivery Network(s) as middleboxes).
- Shadowsocks: use the socks5 proxy provided by shadowsocks client/server.

All you need is to call `Cronet_EngineParams_envoy_url_set(engine_params, "ENVOY_URL")` where ENVOY_URL is your HTTPS URL or shadowsocks' socks5 proxy address.

Visit native/README.md and android/README.md for more technical details.

## What is Cronet
Cronet is the networking stack of Chromium put into a library for use on mobile. This is the same networking stack that is used in the Chrome browser by over a billion people. It offers an easy-to-use, high performance, standards-compliant, and secure way to perform HTTP requests.. On Android, Cronet offers its own Java asynchronous API as well as support for the java.net.HttpURLConnection API. Cronet takes advantage of multiple technologies that reduce the latency and increase the throughput of the network requests that your app needs to work.

## All About Envoy
Envoy is built on top of cronet which offers support for OkHttp, Volley, WebView, Cronet basic and java.net.HttpURLConnection.

We have built robust, unique, censorship-defeating tools and services that are having a big impact in some country where some website and their content are censored

This tool is derived from [Cronet](https://chromium.googlesource.com/chromium/src/+/master/components/cronet/).

[Cronet](https://chromium.googlesource.com/chromium/src/+/master/components/cronet/) is the networking stack of Chromium put into a library for use on mobile. This is the same networking stack that is used in chrome browser by over a billion user.

So it will work with all request type which is mostly used in development.
It is used to make app which is resistant to **censorship**

## What is Envoy And Where It can be used

As name suggest envoy is a **representative** or  **messenger**

Like it our tool will work as messenger or representative for certain website or web content access without worrying of censorship as our tool will work as representative.

As earlier said it can be used to make app resistant to  **censorship**.

Also it can be used as proxy tool communication for secure web access to your app.
E.g. if facebook and YouTube is not accessible to country like china and you want to make app which shows facebook content or YouTube content then you can use this tool to show your content.

It can be used as partial content showing or can be used to make whole server communication with this tool depending upon your requirement.

Also if you don't want to use envoy proxy then also you can use this tool to get benefit of cronet library hassle-free. And in future if you want then you just have to simply define envoy url to bypass censorship.

## App examples

Go to patches for each application in directory `apps` to learn how to integrate `envoy` library, or test with our demo apk files.

1. [Wikipedia](https://github.com/wikimedia/apps-android-wikipedia): `./gradlew clean assembleDevDebug`, [demo apk](https://envoy.greatfire.org/static/wikipedia-prod.apk), and the [migration guide](apps/wikipedia.md).
2. [DuckDuckGo](https://github.com/duckduckgo/Android): `./gradlew assembleDebug`, [demo apk](https://envoy.greatfire.org/static/duckduckgo-5.41.0-debug.apk)
3. WordPress:
   1. [WordPress-FluxC-Android](https://github.com/wordpress-mobile/WordPress-FluxC-Android): `echo "sdk.dir=YOUR_SDK_DIR" > local.properties && ./gradlew fluxc:build`
   2. [WordPress-Android](https://github.com/wordpress-mobile/WordPress-Android): set `wp.oauth.app_id` and `wp.oauth.app_secret`, then `cp gradle.properties-example gradle.properties && ./gradlew assembleVanillaDebug`

You can submit more apps with `git -c diff.noprefix=false format-patch --numbered --binary HEAD~`.

## Release steps
1. Rebuild cronet-debug.aar and cronet-release.aar: run `./native/build_cronet.sh debug` and `./native/build_cronet.sh release`
2. Rebuild envoy: `./android/build-envoy.sh`
3. Rebuild demo apps: `./apps/build-apps.sh`

## History
1. [Google to reimplement curl in libcrurl | daniel.haxx.se](https://daniel.haxx.se/blog/2019/06/19/google-to-reimplement-curl-in-libcrurl/), [Simplified Chinese](https://www.oschina.net/news/107711/google-to-reimplement-curl-in-libcrurl)
2. [973603 - [Cronet] libcurl wrapper library using Cronet API - chromium](https://bugs.chromium.org/p/chromium/issues/detail?id=973603)
