C and Java Library derived from chromium [cronet](https://chromium.googlesource.com/chromium/src/+/master/components/cronet/).

Technical details are explained [here](native/README.md).

* for pure c and java cronet library, see directory `native/`.
* for the android library, import directory `android/` from android studio or build with gradle from the command line.

## App examples

Go to patches for each application in directory `apps` to learn how to integrate `envoy` library, or test with our demo apk files.

1. [Wikipedia](https://github.com/wikimedia/apps-android-wikipedia): `./gradlew clean assembleDevDebug`, [demo apk](https://en.greatfire.org/demos/wikipedia-dev-debug.apk), and the [migration guide](apps/wikipedia.md).
2. [DuckDuckGo](https://github.com/duckduckgo/Android): `./gradlew assembleDebug`, [demo apk](https://en.greatfire.org/demos/duckduckgo-5.41.0-debug.apk)
3. Wordpress:
   1. [WordPress-FluxC-Android](https://github.com/wordpress-mobile/WordPress-FluxC-Android): `echo "sdk.dir=YOUR_SDK_DIR" > local.properties && ./gradlew fluxc:build`
   2. [WordPress-Android](https://github.com/wordpress-mobile/WordPress-Android): set `wp.oauth.app_id` and `wp.oauth.app_secret`, then `cp gradle.properties-example gradle.properties && ./gradlew assembleVanillaDebug`

You can submit more apps with `git -c diff.noprefix=false format-patch --numbered --binary HEAD~`.

## History
1. [Google to reimplement curl in libcrurl | daniel.haxx.se](https://daniel.haxx.se/blog/2019/06/19/google-to-reimplement-curl-in-libcrurl/), [谷歌想实现自己的 curl，为什么？](https://www.oschina.net/news/107711/google-to-reimplement-curl-in-libcrurl)
2. [973603 - [Cronet] libcurl wrapper library using Cronet API - chromium](https://bugs.chromium.org/p/chromium/issues/detail?id=973603)
