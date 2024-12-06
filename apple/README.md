# Envoy for Apple Platforms

[![Version](https://img.shields.io/cocoapods/v/GreatfireEnvoy.svg?style=flat)](https://cocoapods.org/pods/GreatfireEnvoy)
[![License](https://img.shields.io/cocoapods/l/GreatfireEnvoy.svg?style=flat)](https://cocoapods.org/pods/GreatfireEnvoy)
[![Platform](https://img.shields.io/cocoapods/p/GreatfireEnvoy.svg?style=flat)](https://cocoapods.org/pods/GreatfireEnvoy)

This is the implementation for Apple platforms of the original [Envoy for Android](https://github.com/greatfire/envoy).

Greatfire's 'Envoy is a manager for various proxy implementations.

It will automatically find the best working proxy and provide the necessary configuration
via helper methods.

Supported Proxies are:
- Envoy HTTP proxy (partial support on Apple platforms)
- V2Ray
- Hysteria2
- Pluggable Transports together with an Envoy HTTP proxy or a SOCKS5 proxy:
  - Meek
  - Obfs4
  - WebTunnel
  - Snowflake

Since Apple platforms come with some limitations, Envoy for Apple Platforms has some 

## Differences compared to Envoy for Android:

- It is *not* based on [Cronet](https://chromium.googlesource.com/chromium/src/+/refs/heads/main/components/cronet/README.md),
  since Google discontinued Cronet support for iOS a while ago.
  Obviously, because of Apple's lack of evolvement of the [`NSURLProtocol`](https://developer.apple.com/documentation/foundation/nsurlprotocol))
  interface, which Cronet used, and a lack of any viable replacements.
  
- The custom Envoy HTTP proxy is only partially supported with `WKWebView`, due to a lack of usable hooks
  to modify requests properly. In an environment, where you can afford to send some requests over the
  clearnet, this does not pose a problem. However, if you need to keep your users safe from observation
  and blocking, **do not use it**!
  
- Hence, Envoy for Apple Platforms supports a replacement: SOCKS5 proxies.
  This should be used in conjunction with all Pluggable Transports (Obfs4, Meek, WebTunnel, Snowflake), which
  need an additional protocol as their payload, since they're about obfuscation only, and don't handle
  traffic routing. On Linux servers, we recommend to use [Dante](https://www.inet.no/dante/)
  as the counterpart behind a Pluggable Transport.
  
- Hysteria: Envoy for Apple Platforms only supports Hysteria 2. Hysteria 2 is a complete rewrite and is
  totally incompatible with Hysteria 1. Hysteria 1 is discontinued. Currently Envoy for Android stays
  on Hysteria 1, but is bound to update to Hysteria 2, eventually.
  

## Apple Platforms Limitations:

- Most notably, [`WKWebView`](https://developer.apple.com/documentation/webkit/wkwebview) only [supports 
  SOCKS5 (and HTTP) proxies beginning with iOS 17/macOS 14](https://developer.apple.com/documentation/webkit/wkwebsitedatastore/4264546-proxyconfigurations).
  If you need to keep your users safe and secure, and not only provide proxying on a best-effort basis,
  *and* if you use `WKWebView`, then **do not support iOS below version 17 and macOS below version 14**!

- If you're still allowed to use [`UIWebView`](https://developer.apple.com/documentation/uikit/uiwebview),
  although it's highly deprecated and Apple doesn't allow new submissions with it, 
  you can use the `EnvoyUrlProtocol`.
  
- If you want to use [`URLSession`](https://developer.apple.com/documentation/foundation/nsurlsession) or the legacy
  [`NSURLConnection`](https://developer.apple.com/documentation/foundation/nsurlconnection/), 
  it is highly recommended, though, that you directly use `Envoy.shared.getProxyDict()` and `Envoy.shared.maybeModify(:)`,
  instead of leveraging the `EnvoyUrlProtocol`!
  
- `WKWebView` configurations cannot be changed after initialization. So you will need to wait with that, until
  `Envoy.shared.start()` was run.
  
## `libcurl` Support:

Since version 0.3.0, Envoy also has `libcurl` support.

If you use `GreatfireEnvoy/Curl` as the dependency declaration, Envoy will take in additional 
dependencies:
- https://github.com/greatfire/SwiftyCurl
- https://github.com/greatfire/curl-apple

The latter provides `libcurl` compiled for iOS and macOS, the former provides a nice abstraction 
over the somewhat cumbersome `libcurl` API.

If `libcurl` is used, Envoy will change the following ways:
- It will init and publicly share a `SwiftyCurl` object on `SwiftyCurl.shared`, which it uses 
  itself for all requests.
- Proxy testing will use `libcurl` instead of `URLSession`.
- `EnvoySchemeHandler` (used in `EnvoyWebView`) will use `libcurl` instead of `URLSession`.
- `EnvoyUrlProtocol` will use `libcurl` instead of `URLSession`.
- It will provide helper methods `Envoy.shared.task(from:with:)` and `Proxy.task(from:with:)` which
  you can use to create your own `libcurl` tasks.
  
### Benefits

Using `libcurl` improves support for the custom Envoy proxy type:

- Supports `Host` header mangling, hence domain fronting: `libcurl` can use one domain for DNS 
  resolution and TLS SNI, and another domain for the HTTP `Host` header to support domain fronting
  scenarios.
  
- Supports hard-coded DNS resolution: You can provide hard-coded IP addresses for your custom Envoy 
  proxy, and still use the provided domain for TLS SNI.
  
- Redirects are not automatically followed: We can rewrite redirects to tunnel them over a custom
  Envoy proxy the same way as we can do with the initial request.
  

## Supported Proxies

### Custom Envoy HTTP Proxy

Example config URLs:

`https://proxy.example.com/proxy/`
`envoy://?url=https://proxy.example.com/proxy/&salt=abcdefghijklmnop&header_foobar=abcdefg&address=127.0.0.1`

- Works with `URLSession` and `URLConnection` requests by using `Envoy.shared.maybeModify(:)` with 
  the `URLRequest` object. Note, that you can *not* use domain fronting, hard-coded IP addresses 
  and rewritten redirects like that, so only specific custom Envoy proxies work, and only, if 
  there's no server-triggered redirects to blocked addresses!
- Works with `SwiftyCurl` requests by using `Envoy.shared.task(for:with:)` with the `URLRequest` 
  object. This is the preferred way, if you use domain-fronted proxies, need to hard-code IP 
  addresses and want to control redirects.
- Works with `UIWebView` by using `EnvoyUrlProtocol`.
- Works partially with `WKWebView` by using `EnvoyWebView` which leverages `EnvoySchemeHandler`.
  **ATTENTION**: This works by rewriting the `https` scheme to `envoy-https` and then let the request
  be treated by the `EnvoySchemeHandler`. This works for all requests, which are directly issued by
  the user, for requests which were started by a navigation event (i.e. where the user clicked on a link)
  and for all *relative* URLs used in the requested document.
  **It will not work** for fully qualified URLs which are referenced in any requested documents, stylesheets,
  SVG files, JavaScript files and others!
  Hence, use of the custom Envoy HTTP proxy is not recommended with `WKWebView` and can only be supported
  on a best-effort basis, where the user isn't concerned about having some requests go over the clearnet
  which might potentially be recorded or blocked! 
  
### V2Ray

Example config URLs:

`v2ws://127.0.0.1:12345?id=00000000-0000-0000-0000-00000000000&path=/websocket/`
`v2wechat://127.0.0.1:12346?id=00000000-0000-0000-0000-00000000000`
`v2srtp://127.0.0.1:12347?id=00000000-0000-0000-0000-00000000000`

- All V2Ray proxy modes are fully supported.
- Works with `URLSession`, `URLConnection`, `UIWebView` and `SwiftyCurl` by using 
  `Envoy.shared.getProxyDict()`.
- Works with `WKWebView` from iOS 17 and macOS 14 onwards, by using `Envoy.shared.getProxyConfig()`
  resp. `EnvoyWebView`.

### Hysteria 2

- Fully supported by using the defined [Hysteria 2 URI Scheme](https://v2.hysteria.network/docs/developers/URI-Scheme/).
- Works with `URLSession`, `URLConnection`, `UIWebView` and `SwiftyCurl` by using 
  `Envoy.shared.getProxyDict()`.
- Works with `WKWebView` from iOS 17 and macOS 14 onwards, by using `Envoy.shared.getProxyConfig()`
  resp. `EnvoyWebView`.

### Pluggable Transport: Meek

Example config URLs:

`meek://?url=https://cdn.example.com/&front=.wellknown.org&tunnel=socks5://127.0.0.1:12345`
`meek://?url=https://cdn.example.com/&front=.wellknown.org&tunnel=https://proxy.example.com/proxy/`

- Fully supported when using a SOCKS5 proxy as the PT payload via `EnvoySocksForwarder`.
- The Custom Envoy HTTP proxy as PT payload is only partially supported and therefore advised 
  against. See [Custom Envoy HTTP Proxy](#custom-envoy-http-proxy).
- Works with `URLSession`, `URLConnection`, `UIWebView` by using `Envoy.shared.maybeModify(:)`
  and `Envoy.shared.getProxyDict()`.
- Works with `SwiftyCurl` by using `Envoy.shared.task(from:with:)`.
- Works with `WKWebView` from iOS 17 and macOS 14 onwards **when a SOCKS5 proxy is used**, 
  by using `Envoy.shared.getProxyConfig()` resp. `EnvoyWebView`.

### Pluggable Transport: Obfs4

Example config URLs:

`obfs4://?cert=abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqr&tunnel=socks5://192.168.254.254:12345`
`obfs4://?cert=abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqr&tunnel=https://proxy.example.com/proxy/`

- Fully supported when using a SOCKS5 proxy as the PT payload via `EnvoySocksForwarder`.
- The Custom Envoy HTTP proxy as PT payload is only partially supported and therefore advised 
  against. See [Custom Envoy HTTP Proxy](#custom-envoy-http-proxy).
- Works with `URLSession`, `URLConnection`, `UIWebView` by using `Envoy.shared.maybeModify(:)` 
  and `Envoy.shared.getProxyDict()`.
- Works with `SwiftyCurl` by using `Envoy.shared.task(from:with:)`.
- Works with `WKWebView` from iOS 17 and macOS 14 onwards **when a SOCKS5 proxy is used**, 
  by using `Envoy.shared.getProxyConfig()` resp. `EnvoyWebView`.

### Pluggable Transport: WebTunnel

Example config URLs:

`webtunnel://?url=https://example.com/abcdefghijklm&ver=0.0.1&tunnel=socks5://127.0.0.1:12345`
`webtunnel://?url=https://example.com/abcdefghijklm&ver=0.0.1&tunnel=https://proxy.example.com/proxy/`

- Fully supported when using a SOCKS5 proxy as the PT payload via `EnvoySocksForwarder`.
- The Custom Envoy HTTP proxy as PT payload is only partially supported and therefore advised 
  against. See [Custom Envoy HTTP Proxy](#custom-envoy-http-proxy).
- Works with `URLSession`, `URLConnection`, `UIWebView` by using `Envoy.shared.maybeModify(:)` 
  and `Envoy.shared.getProxyDict()`.
- Works with `SwiftyCurl` by using `Envoy.shared.task(from:with:)`.
- Works with `WKWebView` from iOS 17 and macOS 14 onwards **when a SOCKS5 proxy is used**, 
  by using `Envoy.shared.getProxyConfig()` resp. `EnvoyWebView`.

### Pluggable Transport: Snowflake

Example config URLs:

`snowflake://?broker=https://broker.example.com/&front=.wellknown.org&tunnel=socks5://127.0.0.1:12345`
`snowflake://?broker=https://broker.example.com/&front=.wellknown.org&tunnel=https://proxy.example.com/proxy/`

- Fully supported when using a SOCKS5 proxy as the PT payload via `EnvoySocksForwarder`.
- The Custom Envoy HTTP proxy as PT payload is only partially supported and therefore advised 
  against. See [Custom Envoy HTTP Proxy](#custom-envoy-http-proxy).
- Works with `URLSession`, `URLConnection`, `UIWebView` by using `Envoy.shared.maybeModify(:)` 
  and `Envoy.shared.getProxyDict()`.
- Works with `SwiftyCurl` by using `Envoy.shared.task(from:with:)`.
- Works with `WKWebView` from iOS 17 and macOS 14 onwards **when a SOCKS5 proxy is used**,
  by using `Envoy.shared.getProxyConfig()` resp. `EnvoyWebView`.


## Usage

```Swift

import GreatfireEnvoy

Task {
    await Envoy.shared.start(
        // Your proxy URLs. Order is important!
        urls: [
            URL(string: "v2ws://127.0.0.1:12345?id=00000000-0000-0000-0000-00000000000&path=/websocket/")!,
            URL(string: "v2wechat://127.0.0.1:12346?id=00000000-0000-0000-0000-00000000000")!,
            URL(string: "v2srtp://127.0.0.1:12347?id=00000000-0000-0000-0000-00000000000")!,
            URL(string: "hysteria2://abcdefghijklmnopqrstuvwxyzabcdef@example.com:12345/")!,
            URL(string: "meek://?url=https://cdn.example.com/&front=.wellknown.org&tunnel=socks5://127.0.0.1:12345")!,
            URL(string: "obfs4://?cert=abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqr&tunnel=socks5://127.0.0.1:12345")!,
            URL(string: "webtunnel://?url=https://example.com/abcdefghijklm&ver=0.0.1&tunnel=socks5://127.0.0.1:12345")!,
            URL(string: "snowflake://?broker=https://broker.example.com/&front=.wellknown.org&tunnel=socks5://127.0.0.1:12345")!,
            URL(string: "envoy://?url=https://proxy.example.com/proxy/&salt=abcdefghijklmnop&header_foobar=abcdefg&address=127.0.0.1")!,
        ],

        // The test URL which determines success.
        // The example given here is the default, so just provide this argument, if you want to use another test endpoint.
        test: .init(url: URL(string: "https://www.google.com/generate_204")!, expectedStatusCode: 204),

        // Will test a clearnet connection first, if set to `true`!
        testDirect: true)


    // Alternatively, you can use this, if you don't need to rely on proxy URL strings:

    await Envoy.shared.start(
        // Your proxies. Order is important!
        proxies: [
            .v2Ray(type: .ws, host: "127.0.0.1", port: 12345, id: "00000000-0000-0000-0000-00000000000", path: "/websocket/"),
            .v2Ray(type: .weChat, host: "127.0.0.1", port: 12346, id: "00000000-0000-0000-0000-00000000000", path: nil),
            .v2Ray(type: .srtp, host: "127.0.0.1", port: 12347, id: "00000000-0000-0000-0000-00000000000", path: nil),
            .hysteria2(url: URL(string: "hysteria2://abcdefghijklmnopqrstuvwxyzabcdef@example.com:12345/")!),
            .meek(url: URL(string: "https://cdn.example.com/")!, front: ".wellknown.org", tunnel: .socks5(host: "127.0.0.1", port: 12345)),
            .obfs4(cert: "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqr", iatMode: 0, tunnel: .socks5(host: "127.0.0.1", port: 12345)),
            .webTunnel(url: URL(string: "https://example.com/abcdefghijklm")!, ver: "0.0.1", tunnel: .socks5(host: "127.0.0.1", port: 12345)),
            .snowflake(ice: nil, broker: URL(string: "https://broker.example.com/")!, fronts: ".wellknown.org", ampCache: nil, sqsQueue: nil, sqsCreds: nil, tunnel: .socks5(host: "127.0.0.1", port: 12345)),
            .envoy(url: URL(string: "https://127.0.0.1/proxy/")!, headers: ["foobar": "abcdefg"], salt: "abcdefghijklmnop"),
        ],

        // The test URL which determines success.
        // The example given here is the default, so just provide this argument, if you want to use another test endpoint.
        test: .init(url: URL(string: "https://www.google.com/generate_204")!, expectedStatusCode: 204),

        // Will test a clearnet connection first, if set to `true`!
        testDirect: true)


    // Another approach is using the [`Proxy`](https://github.com/greatfire/envoy/blob/master/apple/Example/Shared/Proxy.swift) 
    // class provided in the example code, which allows storing the URLs obfuscated in a separate file:
    
    let proxies = Proxy.fetch()
    await Envoy.shared.start(urls: proxies.map({ $0.url }), testDirect: proxies.isEmpty)


    // NOTE: Start your requests only **after** `Envoy.shared.start()`` was run!
        
    let conf = URLSessionConfiguration.ephemeral
    conf.connectionProxyDictionary = Envoy.shared.getProxyDict()

    let session = URLSession(configuration: conf)

    let request = URLRequest(url: URL(string: "https://www.wikipedia.org/")!)

    do {
        let (data, response) = try await session.data(for: Envoy.shared.maybeModify(request))

        print(response)
        print(String(data: data, encoding: .utf8))
    }
    catch {
        print(error)
    }


    // or with `libcurl`:

    if let task = Envoy.shared.task(for: SwiftyCurl.shared, with: request) {
        do {
            let (data, response) = try await task.resume()

            print(response)
            print(String(data: data, encoding: .utf8))
        }
        catch {
            print(error)
        }
    }


    // NOTE: Only initialize `EnvoyWebView` **after** `Envoy.shared.start()`` was run!
    // (This is a limitation of Apple platforms!)

    let webView = EnvoyWebView(frame: .zero)
    view.addSubview(webView)

    webView.load(request)
}

```


## Example

To run the example project, clone the repo, and run `pod install` from the Example directory first.

## Requirements

## Installation

Envoy is available through [CocoaPods](https://cocoapods.org). To install
it, simply add the following line to your Podfile:

```ruby
pod 'GreatfireEnvoy'
```

For `libcurl` support, use:

```ruby
pod 'GreatfireEnvoy/Curl'
```

Envoy also supports Swift Package Manager.

Just add the [git repo](https://github.com/greatfire/envoy/) to your Xcode package manager UI,
or use this in your own package:

```Swift
dependencies: [
    .package(url: "https://github.com/greatfire/envoy/", revision: "43fcd4e1e983852933e33e92fd79fb8f6d0fc9ae"),
],
```

Unfortunately, Envoy is an older project which started its life as an Android library.
Hence, tagging isn't done in a fashion SPM supports, so unfortunately, you
will need to use specific commits when using SPM.

Note, that when using SPM, `libcurl` and `SwiftyCurl` is always included and will be used by Envoy!



## Author

Benjamin Erhart, berhart@netzarchitekten.com

## License

Envoy is available under the Apache-2.0 license. See the [LICENSE] file for more info.
