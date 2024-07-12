//
//  Envoy.swift
//  Envoy
//
//  Created by Benjamin Erhart on 05.04.24.
//

import Foundation
import Network
import CryptoKit
import IEnvoyProxy

/**
 Envoy is its own proxy method as well as a collection of other proxies.
 */
@objc
@objcMembers
public class Envoy: NSObject {

    /**
     The currently supported proxies.
     */
    public enum Proxy: Equatable, CustomStringConvertible {

        /**
         The different types of V2Ray transports.
         */
        public enum V2RayType: String {

            /**
             WebSocket
             */
            case ws = "v2ws"

            /**
             WeChat
             */
            case weChat = "v2wechat"

            /**
             SRTP
             */
            case srtp = "v2srtp"
        }

        /**
         No proxy used, connect directly.
         */
        case direct

        /**
         The special Envoy HTTP proxy.

         NOTE: Some headers are reserved and cannot be modified with the `URLSession` HTTP client.
         Most notably, this includes the `Host` header. See
         [Reserved Headers](https://developer.apple.com/documentation/foundation/nsurlrequest#1776617)
         for details.

         ALSO NOTE: The iOS version doesn't support some of the arguments which are handled by Cronet in the Android version.

         - parameter url: The URL to the Envoy proxy.
         - parameter headers: Additional headers to send.
         - parameter salt: A predefined salt for a cache-busting query parameter named `_digest`. Will be randomly generated on each request, if not given.
         */
        case envoy(url: URL, headers: [String: String], salt: String?)

        /**
         V2Ray proxy.

         - parameter type: The transport type to use for V2Ray.
         - parameter host: The V2Ray server. Preferrably an IP address.
         - parameter port: The V2Ray server port.
         - parameter id: The shared secret UUID for authentication.
         - parameter path: The WebSocket path. Only used with the ``V2RayType/ws`` type.
         */
        case v2Ray(type: V2RayType, host: String, port: Int, id: String, path: String?)

        /**
         Meek transport which obfuscates another proxy.

         - parameter url: The URL to the meek server.
         - parameter front: The front domain to use instead of the Meek server's name in the TLS SNI.
         - parameter tunnel: Only ``envoy(url:headers:salt:)`` proxies are supported, currently.
         */
        indirect case meek(url: URL, front: String, tunnel: Proxy)

        /**
         Obfs4 transport which obfuscates another proxy.

         - parameter cert: The pulbic component of the Obfs4 server's Curve25519 key.
         - parameter iatMode: The "Inter-Ariival Time" timing randomization mode.
         - parameter tunnel: Only ``envoy(url:headers:salt:)`` proxies are supported, currently.
         */
        indirect case obfs4(cert: String, iatMode: Int, tunnel: Proxy)

        /**
         Snowflake transport which obfuscates another proxy.

         - parameter ice: Comma-separated list of ICE servers. If `nil` defaults to ``Envoy.defaultIceServers``.
         - parameter broker: URL of signaling broker.
         - parameter fronts: Comma-separated list of front domains.
         - parameter ampCache: URL of AMP cache to use as a proxy for signaling.
         - parameter sqsQueue: URL of SQS Queue to use as a proxy for signaling.
         - parameter sqsCreds: Credentials to access SQS Queue.
         - parameter tunnel: Only ``envoy(url:headers:salt:)`` proxies are supported, currently.
         */
        indirect case snowflake(ice: String?, broker: URL, fronts: String, ampCache: String?, sqsQueue: URL?, sqsCreds: String?, tunnel: Proxy)

        /**
         Hysteria 2 proxy.

         - parameter url: A Hysteria 2 connection URL. See [https://v2.hysteria.network/docs/developers/URI-Scheme/](Hysteria 2 URI-Scheme)
         */
        case hysteria2(url: URL)

        /**
         A SOCKS 5 proxy.

         This is only useful as a tunnel for the `obfs4`, `meek` and `snowflake` transports!
         */
        case socks5(host: String, port: Int)

        public var description: String {
            switch self {
            case .direct:
                return "direct"

            case .envoy(let url, let headers, let salt):
                return "envoy url=\(url), headers=\(headers), salt=\(salt ?? "(nil)")"

            case .v2Ray(let type, let host, let port, let id, let path):
                return "v2ray type=\(type.rawValue), host=\(host), port=\(port), id=\(id), path=\(path ?? "(nil)")"

            case .meek(let url, let front, let tunnel):
                return "meek url=\(url), front=\(front), tunnel=\(tunnel)"

            case .obfs4(let cert, let iatMode, let tunnel):
                return "obfs4 cert=\(cert), iatMode\(iatMode), tunnel=\(tunnel)"

            case .snowflake(let ice, let broker, let fronts, let ampCache, let sqsQueue, let sqsCreds, let tunnel):
                return "snowflake ice=\(ice ?? Envoy.defaultIceServers), broker=\(broker), fronts=\(fronts), "
                    + "ampCache=\(ampCache ?? "(nil)"), sqsQueue=\(sqsQueue?.absoluteString ?? "(nil)"), "
                    + "sqsCreds=\(sqsCreds ?? "(nil)"), tunnel=\(tunnel)"

            case .hysteria2(let url):
                return "hysteria2 url=\(url)"

            case .socks5(let host, let port):
                return "SOCKS5 host=\(host), port=\(port)"
            }
        }

        public var port: Int? {
            switch self {
            case .v2Ray(let type, _, _, _, _):
                switch type {
                case .ws:
                    return IEnvoyProxyV2rayWsPort()

                case .weChat:
                    return IEnvoyProxyV2rayWechatPort()

                case .srtp:
                    return IEnvoyProxyV2raySrtpPort()
                }

            case .meek:
                return IEnvoyProxyMeekPort()

            case .obfs4:
                return IEnvoyProxyObfs4Port()

            case .snowflake:
                return IEnvoyProxySnowflakePort()

            case .hysteria2:
                return IEnvoyProxyHysteria2Port()

            case .socks5(_, let port):
                return port

            default:
                return nil
            }
        }

        /**
         Start the proxy/transport, if needed for this type.
         */
        public func start() {
            switch self {
            case .meek, .obfs4, .snowflake:
                if let path = Envoy.ptStateDir?.path {
                    IEnvoyProxy.setStateLocation(path)
                }

            default:
                break
            }

            switch self {
            case .v2Ray(let type, let host, let port, let id, let path):
                switch type {
                case .ws:
                    IEnvoyProxyStartV2RayWs(host, String(port), path, id)

                case .weChat:
                    IEnvoyProxyStartV2RayWechat(host, String(port), id)

                case .srtp:
                    IEnvoyProxyStartV2raySrtp(host, String(port), id)
                }

            case .meek, .obfs4:
                IEnvoyProxyStartLyrebird("INFO", Envoy.ptLogging, false)

            case .snowflake(let ice, let broker, let fronts, let ampCache, let sqsQueue, let sqsCreds, _):
                var fronts = fronts.split(separator: ",").map { String($0) }

                for i in 0 ..< fronts.count {
                    if fronts[i].hasPrefix(".") {
                        fronts[i] = randomPrefix().appending(fronts[i])
                    }
                }

                IEnvoyProxyStartSnowflake(
                    ice ?? Envoy.defaultIceServers, broker.absoluteString, fronts.joined(separator: ","), ampCache,
                    sqsQueue?.absoluteString, sqsCreds, Envoy.ptLogging ? "snowflake.log" : nil,
                    true, true, false, 1)

            case .hysteria2(let url):
                IEnvoyProxyStartHysteria2(url.absoluteString)

            default:
                break
            }
        }

        /**
         Stop the proxy/transport, if needed for this type.
         */
        public func stop() {
            switch self {
            case .v2Ray(let type, _, _, _, _):
                switch type {
                case .ws:
                    IEnvoyProxyStopV2RayWs()

                case .weChat:
                    IEnvoyProxyStopV2RayWechat()

                case .srtp:
                    IEnvoyProxyStopV2RaySrtp()
                }

            case .meek, .obfs4:
                IEnvoyProxyStopLyrebird()

            case .snowflake:
                IEnvoyProxyStopSnowflake()

            case .hysteria2:
                IEnvoyProxyStopHysteria2()

            default:
                break
            }
        }

        /**
         Modify the given ``URLRequest`` if need be.

         The Envoy proxy type is a custom HTTP proxy which needs to make some modifications to your original request.

         The original host will be replaced with the Envoy proxy host and the real URL sent via HTTP header fields.

         For idempotent request methods like `GET` and `HEAD`, an additional cache-busting digest wil lbe appended to the query.

         This is a no-op for all other types of proxies.

         - parameter request: A ``URLRequest`` which might need to be modified for this type of proxy.
         - returns: An eventualy modified ``URLRequest`` object instead of the original one.
         */
        public func maybeModify(_ request: URLRequest) -> URLRequest {
            var url: URL
            let headers: [String: String]
            let salt: String?

            switch self {
            case .envoy(let u, let h, let s):
                url = u
                headers = h
                salt = s

            case .meek(_, _, let tunnel):
                if case .envoy(let u, let h, let s) = tunnel {
                    url = u
                    headers = h
                    salt = s
                }
                else {
                    return request
                }

            case .obfs4(_, _, let tunnel):
                if case .envoy(let u, let h, let s) = tunnel {
                    url = u
                    headers = h
                    salt = s
                }
                else {
                    return request
                }

            case .snowflake(_, _, _, _, _, _, let tunnel):
                if case .envoy(let u, let h, let s) = tunnel {
                    url = u
                    headers = h
                    salt = s
                }
                else {
                    return request
                }

            default:
                return request
            }

            guard !(request.url?.absoluteString.hasPrefix(url.absoluteString) ?? false) else {
                return request
            }

            switch request.httpMethod {
            case "GET", "HEAD":
                if let digest = digest(request.url, salt) {
                    url = url.appendingQueryItem("_digest", digest)
                }

            default:
                break
            }

            var modified = request
            modified.url = url
            modified.cachePolicy = .reloadIgnoringLocalAndRemoteCacheData

            for header in headers {
                modified.addValue(header.value, forHTTPHeaderField: header.key)
            }

            if let host = request.url?.host {
                modified.setValue(host, forHTTPHeaderField: "Host-Orig")
            }

            if let url = request.url {
                // Because of redirects, this URL can have a `_digest` query. Remove it again.
                modified.setValue(url.removingQueryItems(named: "_digest").absoluteString, forHTTPHeaderField: "Url-Orig")
            }

            return modified
        }

        /**
         Will revert all modifications ``maybeModify(_:)`` eventually did.

         This is useful, if you need the ``URLRequest`` object for displaying information to the user.

         For non-Envoy proxies, this is a no-op.

         - parameter request: The eventually modified ``URLRequest``.
         - returns: An umodfied version of the request object, if this is the Envoy proxy type.
         */
        public func revertModification(_ request: URLRequest) -> URLRequest {
            guard case .envoy(_, let headers, _) = self else {
                return request
            }

            var reverted = request

            if let url = reverted.value(forHTTPHeaderField: "Url-Orig"),
               let url = URL(string: url)
            {
                reverted.url = url
            }

            reverted.setValue(nil, forHTTPHeaderField: "Host-Orig")
            reverted.setValue(nil, forHTTPHeaderField: "Url-Orig")

            for header in headers {
                if let val = reverted.value(forHTTPHeaderField: header.key) {
                    if val == header.value {
                        reverted.setValue(nil, forHTTPHeaderField: header.key)
                    }
                    else if val.contains(header.value) {
                        var parts = val.split(separator: ",")
                        parts.removeAll(where: { $0 == header.value })
                        reverted.setValue(parts.joined(separator: ","), forHTTPHeaderField: header.key)
                    }
                }
            }

            return reverted
        }

        /**
         For proxies/transports that require a local SOCKS5 proxy, this will return a usable proxy dictionary, otherwise `nil`.

         This proxy dictionary can be used with ``URLSessionConfiguration/connectionProxyDictionary``
         and ``CFReadStreamSetProperty(_:_:_:)``.

         - parameter forceTransport: If set to true, will return the settings for the transport, despite a SOCKS5 tunnel is being used with the transport.
         - returns: A proxy dictionary or `nil` if none needed.
         */
        public func getProxyDict(forceTransport: Bool = false) -> [AnyHashable: Any]? {
            guard let port = port else {
                return nil
            }

            switch self {
            case .v2Ray, .hysteria2:
                return getSocks5Dict(port)

            case .snowflake(_, _, _, _, _, _, let tunnel):
                switch forceTransport ? .direct : tunnel {
                case .socks5:
                    return getSocks5Dict(Int(EnvoySocksForwarder.port))

                default:
                    return getSocks5Dict(port)
                }

            case .meek(let url, var front, let tunnel):
                switch forceTransport ? .direct : tunnel {
                case .socks5:
                    return getSocks5Dict(Int(EnvoySocksForwarder.port))

                default:
                    // If needed, generate randomized host name prefix.
                    if front.hasPrefix(".") {
                        front = randomPrefix().appending(front)
                    }

                    return getSocks5Dict(port, arguments: ["url": url.absoluteString, "front": front])
                }

            case .obfs4(let cert, let iatMode, let tunnel):
                switch forceTransport ? .direct : tunnel {
                case .socks5:
                    return getSocks5Dict(Int(EnvoySocksForwarder.port))

                default:
                    return getSocks5Dict(port, arguments: ["cert": cert, "iat-mode": String(iatMode)])
                }

            default:
                return nil
            }
        }

        /**
         For proxies/transports that require a local SOCKS5 proxy, this will return a usable ``ProxyConfiguration`` object, otherwise `nil`.

         This proxy dictionary can be used with ``URLSessionConfiguration/proxyConfigurations``
         and ``WKWebViewConfiguration/websiteDataStore/proxyConfigurations``.

         - parameter forceTransport: If set to true, will return the settings for the transport, despite a SOCKS5 tunnel is being used with the transport.
         - returns: A ``ProxyConfiguration`` object or `nil` if none needed.
         */
        @available(iOS 17.0, macOS 14.0, *)
        public func getProxyConfig(forceTransport: Bool = false) -> ProxyConfiguration? {
            guard let port = port else {
                return nil
            }

            switch self {
            case .v2Ray, .snowflake, .hysteria2:
                return getSocks5Config(port)

            case .meek(let url, var front, _):
                // If needed, generate randomized host name prefix.
                if front.hasPrefix(".") {
                    front = randomPrefix().appending(front)
                }

                return getSocks5Config(port, arguments: ["url": url.absoluteString, "front": front])

            case .obfs4(let cert, let iatMode, let tunnel):
                switch forceTransport ? .direct : tunnel {
                case .socks5:
                    return getSocks5Config(Int(EnvoySocksForwarder.port))

                default:
                    return getSocks5Config(port, arguments: ["cert": cert, "iat-mode": String(iatMode)])
                }

            default:
                return nil
            }
        }

        /**
         Tells you, if you can run this proxy in parallel with another.

         Some proxies cannot be run in parallel, because they would need a restart with the changed configuration.

         - parameter proxy: Another proxy which you might want to run in parallel.
         - returns: True, if this proxy can run with the other proxy in parallel, false, if not.
         */
        public func isParallelizable(with proxy: Proxy) -> Bool {
            switch self {
            case .v2Ray(let type, _, _, _, _):
                switch proxy {
                case .v2Ray(let type2, _, _, _, _):
                    return type != type2

                default:
                    return true
                }

            case .snowflake:
                switch proxy {
                case .snowflake:
                    return false

                default:
                    return true
                }

            case .hysteria2:
                switch proxy {
                case .hysteria2:
                    return false

                default:
                    return true
                }

            default:
                return true
            }
        }


    }


    // MARK: Public Properties

    public static let shared = Envoy()

    /**
     The Pluggble Transports State Directory.

     This is needed for ``Proxy/meek(url:front:tunnel:)``, ``Proxy/obfs4(url:front:tunnel:)``
     and ``Proxy/snowflake(ice:broker:fronts:ampCache:sqsQueue:sqsCreds:tunnel:)``
     transports.

     It will default to a directory named "pt_state" in the ``FileManager/SearchPathDirectory/cachesDirectory``
     of the ``FileManager/SearchPathDomainMask/userDomainMask``.

     Change this, if you want to e.g. move it to a shared directory.
     */
    public static var ptStateDir: URL? = FileManager.default
        .urls(for: .cachesDirectory, in: .userDomainMask).first?
        .appendingPathComponent("pt_state", isDirectory: true)

    /**
     Set to `true` to enable logging for Lyrebird (Meek and Obfs4) and Snowflake transports.

     The logs can be found in the ``ptStateDir``.
     */
    public static var ptLogging = false

    public static let defaultIceServers = "stun:stun.l.google.com:19302,stun:stun.sonetel.com:3478,stun:stun.voipgate.com:3478,stun:stun.antisip.com:3478"

    public private(set) var proxy: Proxy = .direct

    /**
     For Objective-C, as the ``Proxy`` enum cannot be represented in Objective-C.
     */
    public var proxyDescription: String {
        proxy.description
    }


    // MARK: Private Properties

    private var forwarder: EnvoySocksForwarder?


    private override init() {
        super.init()
    }


    // MARK: Public Methods

    /**
     Starts Envoy with a set of proxy configuration URLs.

     All valid URLs will be considered as candidates and tested against the provided ``testUrl``
     parameter one after the other until a working one is found.

     Make this the first thing you do on app start.

     Afterwards, your app can make use of the ``getProxyDict()``, ``getProxyConfig()``,
     ``maybeModify(_:)`` and ``revertModification(_:)`` methods without needing to think about the used proxy type.

     - parameter urls: A list of proxy configuration URLs.
     - parameter testURL: An endpoint which should be used for testing, if the proxy works. ATTENTION: A HTTP 204 response is expected!
     - parameter testDirect: Flag, if the direct connection should be tested first.
     */
    public func start(urls: [URL], testUrl: URL = URL(string: "https://www.google.com/generate_204")!, testDirect: Bool = true) async {
        await start(proxies: urls.compactMap { parse($0) }, testUrl: testUrl, testDirect: testDirect)
    }

    /**
     Starts Envoy with a set of proxy configurations.

     All given proxies will be considered as candidates and tested against the provided ``testUrl``
     parameter one after the other until a working one is found.

     Make this the first thing you do on app start.

     Afterwards, your app can make use of the  ``getProxyDict()``, ``getProxyConfig()``,
     ``maybeModify(_:)`` and ``revertModification(_:)`` methods without needing to think about the used proxy type.

     - parameter urls: A list of proxy configurations.
     - parameter testURL: An endpoint which should be used for testing, if the proxy works. ATTENTION: A HTTP 204 response is expected!
     - parameter testDirect: Flag, if the direct connection should be tested first.
     */
    public func start(proxies: [Proxy], testUrl: URL = URL(string: "https://www.google.com/generate_204")!, testDirect: Bool = true) async {
        var candidates = proxies

        if testDirect {
            candidates.insert(.direct, at: 0)
        }

        let testRequest = URLRequest(url: testUrl)

        var parallel = true

        for proxy1 in candidates {
            for proxy2 in candidates {
                if proxy1 != proxy2 && !proxy1.isParallelizable(with: proxy2) {
                    parallel = false
                    break
                }
            }
        }

        if parallel {
            await withTaskGroup(of: (Proxy, Bool).self) { group in
                for proxy in candidates {
                    proxy.start()

                    if case .obfs4(_, _, let tunnel) = proxy, case .socks5 = tunnel {
                        do {
                            forwarder = try EnvoySocksForwarder(proxy).start()
                        }
                        catch {
                            print("[\(String(describing: type(of: self)))] error=\(error)")

                            proxy.stop()

                            continue
                        }
                    }

                    group.addTask {
                        return (proxy, await Self.test(testRequest, with: proxy))
                    }
                }

                while let item = await group.next() {
                    if item.1 {
                        group.cancelAll()
                        proxy = item.0

                        break
                    }
                }

                // Stop all proxies except the selected one.
                candidates
                    .filter({ $0 != proxy })
                    .forEach { $0.stop() }
            }
        }
        else {
            for proxy in candidates {
                proxy.start()

                if case .obfs4(_, _, let tunnel) = proxy, case .socks5 = tunnel {
                    do {
                        forwarder = try EnvoySocksForwarder(proxy).start()
                    }
                    catch {
                        print("[\(String(describing: type(of: self)))] error=\(error)")

                        proxy.stop()

                        continue
                    }
                }

                if await Self.test(testRequest, with: proxy) {
                    self.proxy = proxy
                    break
                }

                proxy.stop()

                forwarder?.stop()
                forwarder = nil
            }
        }


        if case .obfs4(_, _, let tunnel) = proxy, case .socks5 = tunnel {
        }
        else {
            forwarder?.stop()
            forwarder = nil
        }
    }

    /**
     Stop any used proxies/transports. Reset to ``Proxy/direct``.
     */
    public func stop() {
        forwarder?.stop()
        forwarder = nil

        proxy.stop()
        proxy = .direct
    }

    /**
     Modify the given ``URLRequest`` if need be.

     The Envoy proxy type is a custom HTTP proxy which needs to make some modifications to your original request.

     The original host will be replaced with the Envoy proxy host and the real URL sent via HTTP header fields.

     For idempotent request methods like `GET` and `HEAD`, an additional cache-busting digest wil lbe appended to the query.

     This is a no-op for all other types of proxies.

     - parameter request: A ``URLRequest`` which might need to be modified for this type of proxy.
     - returns: An eventualy modified ``URLRequest`` object instead of the original one.
     */
    public func maybeModify(_ request: URLRequest) -> URLRequest {
        proxy.maybeModify(request)
    }

    /**
     Will revert all modifications ``maybeModify(_:)`` eventually did.

     This is useful, if you need the ``URLRequest`` object for displaying information to the user.

     For non-Envoy proxies, this is a no-op.

     - parameter request: The eventually modified ``URLRequest``.
     - returns: An umodfied version of the request object, if this is the Envoy proxy type.
     */
    public func revertModification(_ request: URLRequest) -> URLRequest {
        proxy.revertModification(request)
    }

    /**
     For proxies/transports that require a local SOCKS5 proxy, this will return a usable proxy dictionary, otherwise `nil`.

     This proxy dictionary can be used with ``URLSessionConfiguration/connectionProxyDictionary``
     and ``CFReadStreamSetProperty(_:_:_:)``.

     - returns: A proxy dictionary or `nil` if none needed.
     */
    public func getProxyDict() -> [AnyHashable: Any]? {
        proxy.getProxyDict()
    }

    /**
     For proxies/transports that require a local SOCKS5 proxy, this will return a usable ``ProxyConfiguration`` object, otherwise `nil`.

     This proxy dictionary can be used with ``URLSessionConfiguration/proxyConfigurations``
     and ``WKWebViewConfiguration/websiteDataStore/proxyConfigurations``.

     - returns: A ``ProxyConfiguration`` object or `nil` if none needed.
     */
    @available(iOS 17.0, macOS 14.0, *)
    public func getProxyConfig() -> ProxyConfiguration? {
        proxy.getProxyConfig()
    }


    // MARK: Private Methods

    /**
     Parses a proxy URL.

     - parameter url: The URL describing a proxy with its parameters.
     - returns: A proxy, if one could be identified.
     */
    func parse(_ url: URL) -> Proxy? {
        if url.scheme == "http" || url.scheme == "https" {
            return .envoy(url: url, headers: [:], salt: nil)
        }
        else if url.scheme == "envoy" {
            if let urlc = url.urlc,
               let proxy = Self.extractEnvoyConfig(from: urlc)
            {
                return proxy
            }
        }
        else if let type = Proxy.V2RayType(rawValue: url.scheme ?? "") {
            if let host = url.host,
               !host.isEmpty,
               let port = url.port,
               let urlc = url.urlc,
               let id = urlc.firstQueryItem(of: "id")
            {
                let path = urlc.firstQueryItem(of: "path")

                return .v2Ray(type: type, host: host, port: port, id: id, path: path)
            }
        }
        else if url.scheme == "meek" {
            if let urlc = url.urlc,
               let url = urlc.firstQueryItem(of: "url"),
               let url = URL(string: url),
               let front = urlc.firstQueryItem(of: "front"),
               let tunnel = urlc.firstQueryItem(of: "tunnel"),
               let tunnel = URLComponents(string: "envoy://?url=\(tunnel)"),
               let tunnel = Self.extractEnvoyConfig(from: tunnel)
            {
                return .meek(url: url, front: front, tunnel: tunnel)
            }
        }
        else if url.scheme == "obfs4" {
            if let urlc = url.urlc,
               let cert = urlc.firstQueryItem(of: "cert"),
               let tunnel = urlc.firstQueryItem(of: "tunnel"),
               let tunnel = URLComponents(string: "envoy://?url=\(tunnel)"),
               let tunnel = Self.extractEnvoyConfig(from: tunnel)
            {
                let iatMode = Int(urlc.firstQueryItem(of: "iat-mode") ?? "") ?? 0

                return .obfs4(cert: cert, iatMode: iatMode, tunnel: tunnel)
            }
        }
        else if url.scheme == "snowflake" {
            if let urlc = url.urlc,
               let broker = urlc.firstQueryItem(of: "broker"),
               let broker = URL(string: broker),
               let fronts = urlc.firstQueryItem(of: "fronts") ?? urlc.firstQueryItem(of: "front"),
               let tunnel = urlc.firstQueryItem(of: "tunnel"),
               let tunnel = URLComponents(string: "envoy://?url=\(tunnel)"),
               let tunnel = Self.extractEnvoyConfig(from: tunnel)
            {
                let ice = urlc.firstQueryItem(of: "ice") ?? Self.defaultIceServers
                let ampCache = urlc.firstQueryItem(of: "ampCache")
                let sqsQueue = URL(string: urlc.firstQueryItem(of: "sqsQueue") ?? "")
                let sqsCreds = urlc.firstQueryItem(of: "sqsCreds")

                return .snowflake(
                    ice: ice, broker: broker, fronts: fronts,
                    ampCache: ampCache, sqsQueue: sqsQueue, sqsCreds: sqsCreds, tunnel: tunnel)
            }
        }
        else if url.scheme == "hysteria2" || url.scheme == "hy2" {
            return .hysteria2(url: url)
        }

        return nil
    }

    /**
     Creates a SHA256 digest for the given URL plus a salt.

     If the salt is `nil` will generate a random string as salt.

     - parameter url: URL to hash.
     - parameter salt: Salt to add to URL before hashing. If `nil`, will use a random salt.
     - returns: A SHA256 digest of the URL + (random) salt.
     */
    private static func digest(_ url: URL?, _ salt: String?) -> String? {
        guard let url = url,
              let salt = salt ?? generateRandomString(count: 16),
              let data = (url.absoluteString + salt).data(using: .utf8)
        else {
            return nil
        }

        return SHA256.hash(data: data).map({ String(format: "%02x", $0) }).joined()
    }

    /**
     Generates a random string by BASE64-encoding a given amount of random bytes.

     - parameter count: Number of random bytes to create.
     - returns: BASE64-encoded string of the random bytes.
     */
    private static func generateRandomString(count: Int) -> String? {
        var bytes = [UInt8](repeating: 0, count: count)
        let result = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)

        guard result == errSecSuccess else {
            return nil
        }

        return Data(bytes).base64EncodedString()
    }

    /**
     Create a valid SOCKS5 proxy configuration dictionary for a localhost server with the given port.

     - parameter port: The port number to use.
     - parameter arguments: Pluggable Transport arguments to serialize into username/password. **ATTENTION**: Combined strings cannot be longer than 512 bytes!
     - returns: A proxy configuration dictionary.
     */
    private static func getSocks5Dict(_ port: Int, arguments: [String: String]? = nil) -> [AnyHashable: Any] {
        var dict: [AnyHashable: Any] = [
            kCFProxyTypeKey: kCFProxyTypeSOCKS,
            kCFStreamPropertySOCKSVersion: kCFStreamSocketSOCKSVersion5,
            kCFStreamPropertySOCKSProxyHost: "127.0.0.1",
            kCFStreamPropertySOCKSProxyPort: port]

        if let (user, password) = asPtArguments(arguments) {
            dict[kCFStreamPropertySOCKSUser] = user
            dict[kCFStreamPropertySOCKSPassword] = password
        }

        return dict
    }

    /**
     Create a valid SOCKS5 proxy configuration object for a localhost server with the given port.

     - parameter port: The port number to use.
     - parameter arguments: Pluggable Transport arguments to serialize into username/password. **ATTENTION**: Combined strings cannot be longer than 512 bytes!
     - returns: A ``ProxyConfiguration`` object.
     */
    @available(iOS 17.0, macOS 14.0, *)
    private static func getSocks5Config(_ port: Int, arguments: [String: String]? = nil) -> ProxyConfiguration? {
        guard port <= UInt16.max,
              let port = NWEndpoint.Port(rawValue: UInt16(port))
        else {
            return nil
        }

        let conf = ProxyConfiguration(socksv5Proxy: .hostPort(host: .ipv4(.loopback), port: port))

        if let (user, password) = asPtArguments(arguments) {
            conf.applyCredential(username: user, password: password)
        }

        return conf
    }

    /**
     Converts a dictionary of arguments to a valid Pluggable Transports v1 username/password argument string.

     **ATTENTION**: As per the SOCKS5 specification, the combined returned argument string cannot be longer than 512 bytes!

     See [Pluggable Transports v1 Spec](https://spec.torproject.org/pt-spec/per-connection-args.html).

     - parameter arguments: A dictionary of arguments.
     - returns: `nil` if the `arguments` parameter is `nil` or empty or 2 strings which need to be used as SOCKS5 username/password connection arguments.
     */
    private static func asPtArguments(_ arguments: [String: String]? = nil) -> (username: String, password: String)? {
        guard let arguments = arguments, !arguments.isEmpty else {
            return nil
        }

        let s = arguments.map { "\(escapePtArgument($0))=\(escapePtArgument($1))" }.joined(separator: ";")

        let center = s.index(s.startIndex, offsetBy: s.count / 2)

        // NOTE: The specification says, we should use a NUL character ("\0") as the
        // password, if the argument string is shorter than 255 bytes, but
        // for an unknown reason, that doesn't work with Swift/Apple libs.
        // So, we just always split the string in half and send the first piece as the username
        // and the second as the password.

        return (String(s[s.startIndex ..< center]), String(s[center ..< s.endIndex]))
    }

    /**
     In Pluggable Transports arguments, the characters "\\" (backslash), "=" (equal sign) and ";" (semicolon) need to be escaped.

     See [Pluggable Transports v1 Spec](https://spec.torproject.org/pt-spec/per-connection-args.html).

     - parameter string: An unescaped argument key or value which shall be used in a PT client argument string.
     - returns: An escaped argument key or value.
     */
    private static func escapePtArgument(_ string: String) -> String {
        string
            .replacingOccurrences(of: "\\", with: "\\\\")
            .replacingOccurrences(of: "=", with: "\\=")
            .replacingOccurrences(of: ";", with: "\\;")
    }

    /**
     - returns: A random string between 4 and 16 characters long made out of lowercase a - z characters.
     */
    private static func randomPrefix() -> String {
        let length = Int.random(in: 4 ... 16)
        let letters = String(String.UnicodeScalarView((UInt32("a") ... UInt32("z")).compactMap({ UnicodeScalar($0) })))

        var string = ""

        while string.count < length {
            if let letter = letters.randomElement() {
                string.append(letter)
            }
        }

        return string
    }

    /**
     Extract an Envoy proxy config from a given URL, if it can be found.

     Will ignore some arguments which are only available with Cronet in the Android version.

     - parameter urlc: An Envoy proxy config URL as ``URLComponents``.
     - returns: An ``Proxy/envoy(url:headers:salt:)`` if found, or `nil`.
     */
    private static func extractEnvoyConfig(from urlc: URLComponents) -> Proxy? {
        var dest: URLComponents? = nil
        var address: String? = nil
        var headers = [String: String]()
        var salt: String? = nil

        for item in urlc.queryItems ?? [] {
            if item.name == "url", let value = item.value, !value.isEmpty {
                dest = URLComponents(string: value)
            }
            else if item.name == "address" {
                address = item.value
            }
            else if item.name == "salt", let value = item.value, !value.isEmpty {
                salt = value
            }
            else if item.name.hasPrefix("header_") {
                headers[String(item.name.dropFirst(7))] = item.value
            }
        }

        if !(address?.isEmpty ?? true) {
            dest?.host = address
        }

        if let dest = dest?.url {
            if dest.scheme == "socks5", let host = dest.host, let port = dest.port {
                return .socks5(host: host, port: port)
            }

            return .envoy(url: dest, headers: headers, salt: salt)
        }

        return nil
    }

    /**
     Test a given request with a given proxy.

     - parameter request: The test URL which needs to return 204 on response to be considered valid.
     - parameter proxy: The proxy to test.
     - returns: `true`, if the test request returned HTTP status 204, else false.
     */
    private static func test(_ request: URLRequest, with proxy: Proxy) async -> Bool {
        let conf = URLSessionConfiguration.ephemeral
        conf.connectionProxyDictionary = proxy.getProxyDict()

        let session = URLSession(configuration: conf)

        do {
            let (_, response) = try await session.data(for: proxy.maybeModify(request))

            return (response as? HTTPURLResponse)?.statusCode == 204
        }
        catch {
            return false
        }
    }
}
