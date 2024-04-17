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
public class Envoy {

    /**
     The currently supported proxies.
     */
    public enum Proxy: CustomStringConvertible {

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
         Meek transport.

         This will need another proxy where Meek is just the obfuscating transport.

         Currently here more for reference and for future development than for actual use.

         - parameter url: The URL to the meek server.
         - parameter front: The front domain to use instead of the Meek server's name in the TLS SNI.
         */
        case meek(url: URL, front: String)

        /**
         Obfs4 transport.

         This will need another proxy where Obfs4 is just the obfuscating transport.

         Currently here more for reference and for future development than for actual use.

         - parameter url: The URL to the meek server.
         - parameter front: The front domain to use instead of the Meek server's name in the TLS SNI.
         */
        case obfs4(url: URL, front: String)

        /**
         Snowflake transport.

         This will need another proxy where Snowflake is just the obfuscating transport.

         Currently here more for reference and for future development than for actual use.

         - parameter ice: Comma-separated list of ICE servers.
         - parameter broker: URL of signaling broker.
         - parameter fronts: Comma-separated list of front domains.
         - parameter ampCache: URL of AMP cache to use as a proxy for signaling.
         - parameter sqsQueue: URL of SQS Queue to use as a proxy for signaling.
         - parameter sqsCreds: Credentials to access SQS Queue.
         */
        case snowflake(ice: String, broker: URL, fronts: String, ampCache: String?, sqsQueue: URL?, sqsCreds: String?)

        public var description: String {
            switch self {
            case .direct:
                return "direct"

            case .envoy(let url, let headers, let salt):
                return "envoy url=\(url), headers=\(headers), salt=\(salt ?? "(nil)")"

            case .v2Ray(let type, let host, let port, let id, let path):
                return "v2ray type=\(type.rawValue), host=\(host), port=\(port), id=\(id), path=\(path ?? "(nil)")"

            case .meek(let url, let front):
                return "meek url=\(url), front=\(front)"

            case .obfs4(let url, let front):
                return "obfs4 url=\(url), front=\(front)"

            case .snowflake(let ice, let broker, let fronts, let ampCache, let sqsQueue, let sqsCreds):
                return "snowflake ice=\(ice), broker=\(broker), fronts=\(fronts), "
                    + "ampCache=\(ampCache ?? "(nil)"), sqsQueue=\(sqsQueue?.absoluteString ?? "(nil)"), "
                    + "sqsCreds=\(sqsCreds ?? "(nil)")"
            }
        }

        /**
         Start the proxy/transport, if needed for this type.
         */
        public func start() {
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

            case .meek(let url, var front):
                // If needed, generate randomized host name prefix.
                if front.hasPrefix(".") {
                    front = randomPrefix().appending(front)
                }

                let user = "url=\(url.absoluteString);front=\(front)"

                IEnvoyProxyStartMeek(user, "\0", nil, false, false)

            case .obfs4(let url, var front):
                // If needed, generate randomized host name prefix.
                if front.hasPrefix(".") {
                    front = randomPrefix().appending(front)
                }

                let user = "url=\(url.absoluteString);front=\(front)"

                IEnvoyProxyStartObfs4(user, "\0", nil, false, false)

            case .snowflake(let ice, let broker, let fronts, let ampCache, let sqsQueue, let sqsCreds):
                var fronts = fronts.split(separator: ",").map { String($0) }

                for i in 0 ..< fronts.count {
                    if fronts[i].hasPrefix(".") {
                        fronts[i] = randomPrefix().appending(fronts[i])
                    }
                }

                IEnvoyProxyStartSnowflake(ice, broker.absoluteString, fronts.joined(separator: ","), ampCache,
                                          sqsQueue?.absoluteString, sqsCreds, nil, true, true, false, 1)

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
            guard case .envoy(var url, let headers, let salt) = self,
                  !(request.url?.absoluteString.hasPrefix(url.absoluteString) ?? false)
            else {
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

         - returns: A proxy dictionary or `nil` if none needed.
         */
        public func getProxyDict() -> [AnyHashable: Any]? {
            switch self {
            case .v2Ray(let type, _, _, _, _):
                switch type {
                case .ws:
                    return getSocks5Dict(IEnvoyProxyV2rayWsPort())

                case .weChat:
                    return getSocks5Dict(IEnvoyProxyV2rayWechatPort())

                case .srtp:
                    return getSocks5Dict(IEnvoyProxyV2raySrtpPort())
                }

            case .meek:
                return getSocks5Dict(IEnvoyProxyMeekPort())

            case .obfs4:
                return getSocks5Dict(IEnvoyProxyObfs4Port())

            case .snowflake:
                return getSocks5Dict(IEnvoyProxySnowflakePort())

            default:
                return nil
            }
        }

        /**
         For proxies/transports that require a local SOCKS5 proxy, this will return a usable ``ProxyConfiguration`` object, otherwise `nil`.

         This proxy dictionary can be used with ``URLSessionConfiguration/proxyConfigurations``
         and ``WKWebViewConfiguration/websiteDataStore/proxyConfigurations``.

         - returns: A ``ProxyConfiguration`` object or `nil` if none needed.
         */
        @available(iOS 17.0, *)
        public func getProxyConfig() -> ProxyConfiguration? {
            switch self {
            case .v2Ray(let type, _, _, _, _):
                switch type {
                case .ws:
                    return getSocks5Config(IEnvoyProxyV2rayWsPort())

                case .weChat:
                    return getSocks5Config(IEnvoyProxyV2rayWechatPort())

                case .srtp:
                    return getSocks5Config(IEnvoyProxyV2raySrtpPort())
                }

            case .meek:
                return getSocks5Config(IEnvoyProxyMeekPort())

            case .obfs4:
                return getSocks5Config(IEnvoyProxyObfs4Port())

            case .snowflake:
                return getSocks5Config(IEnvoyProxySnowflakePort())

            default:
                return nil
            }
        }
    }


    // MARK: Public Properties

    public static let shared = Envoy()


    // MARK: Private Properties

    private var proxy: Proxy = .direct


    private init() {
    }


    // MARK: Public Methods

    /**
     Configures Envoy with a set of proxy configuration URLs.

     All valid URLs will be considered as candidates and tested against the provided ``testUrl``
     parameter one after the other until a working one is found.

     Make this the first thing you do on app start.

     Afterwards, your app can make use of the  ``startProxy()``,  ``getProxyDict()``, ``getProxyConfig()``,
     ``maybeModify(_:)`` and ``revertModification(_:)`` methods without needing to think about the used proxy type.

     - parameter urls: A list of proxy configuration URLs.
     - parameter testURL: An endpoint which should be used for testing, if the proxy works. ATTENTION: A HTTP 204 response is expected!
     - parameter testDirect: Flag, if the direct connection should be tested first.
     */
    public func initialize(urls: [URL], testUrl: URL = URL(string: "https://www.google.com/generate_204")!, testDirect: Bool = true) {
        var candidates = [Proxy]()

        for url in urls {
            if url.scheme == "http" || url.scheme == "https" {
                candidates.append(.envoy(url: url, headers: [:], salt: nil))
            }
            else if url.scheme == "envoy" {
                var dest: URLComponents? = nil
                var address: String? = nil
                var headers = [String: String]()
                var salt: String? = nil

                for item in url.queryItems {
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
                    candidates.append(.envoy(url: dest, headers: headers, salt: salt))
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

                    candidates.append(.v2Ray(type: type, host: host, port: port, id: id, path: path))
                }
            }
            else if url.scheme == "meek" {
                if let urlc = url.urlc,
                   let url = urlc.firstQueryItem(of: "url"),
                   let url = URL(string: url),
                   let front = urlc.firstQueryItem(of: "front")
                {
                    candidates.append(.meek(url: url, front: front))
                }
            }
            else if url.scheme == "obfs4" {
                if let urlc = url.urlc,
                   let url = urlc.firstQueryItem(of: "url"),
                   let url = URL(string: url),
                   let front = urlc.firstQueryItem(of: "front")
                {
                    candidates.append(.obfs4(url: url, front: front))
                }
            }
            else if url.scheme == "snowflake" {
                if let urlc = url.urlc,
                   let broker = urlc.firstQueryItem(of: "broker"),
                   let broker = URL(string: broker),
                   let fronts = urlc.firstQueryItem(of: "fronts") ?? urlc.firstQueryItem(of: "front"),
                   let ice = urlc.firstQueryItem(of: "ice")
                {
                    let ampCache = urlc.firstQueryItem(of: "ampCache")
                    let sqsQueue = URL(string: urlc.firstQueryItem(of: "sqsQueue") ?? "")
                    let sqsCreds = urlc.firstQueryItem(of: "sqsCreds")

                    candidates.append(.snowflake(
                        ice: ice, broker: broker, fronts: fronts,
                        ampCache: ampCache, sqsQueue: sqsQueue, sqsCreds: sqsCreds))
                }
            }
        }

        initialize(proxies: candidates, testUrl: testUrl, testDirect: testDirect)
    }

    /**
     Configures Envoy with a set of proxy configurations.

     All given proxies will be considered as candidates and tested against the provided ``testUrl``
     parameter one after the other until a working one is found.

     Make this the first thing you do on app start.

     Afterwards, your app can make use of the  ``startProxy()``,  ``getProxyDict()``, ``getProxyConfig()``,
     ``maybeModify(_:)`` and ``revertModification(_:)`` methods without needing to think about the used proxy type.

     - parameter urls: A list of proxy configurations.
     - parameter testURL: An endpoint which should be used for testing, if the proxy works. ATTENTION: A HTTP 204 response is expected!
     - parameter testDirect: Flag, if the direct connection should be tested first.
     */
    public func initialize(proxies: [Proxy], testUrl: URL = URL(string: "https://www.google.com/generate_204")!, testDirect: Bool = true) {
        var candidates = proxies

        if testDirect {
            candidates.insert(.direct, at: 0)
        }

        // TODO: Test potential candidates and select first one working.
//        let testRequest = URLRequest(url: testUrl)
//
//        for proxy in candidates {
//            var conf = URLSessionConfiguration.ephemeral
//            conf.connectionProxyDictionary = proxy.getProxyDict()
//
//            let session = URLSession(configuration: conf)
//
//            let task = Task {
//                do {
//                    let (_, response) = try await session.data(for: proxy.maybeModify(testRequest))
//
//                    if (response as? HTTPURLResponse)?.statusCode == 204 {
//                        return true
//                    }
//
//                    return false
//                }
//                catch {
//                    return false
//                }
//            }
//        }

        proxy = candidates.first ?? .direct
    }

    /**
     Start the proxy/transport, if needed for the chosen type.
     */
    public func startProxy() {
        proxy.start()
    }

    /**
     Stop the proxy/transport, if needed for the chosen type.
     */
    public func stopProxy() {
        proxy.stop()
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
    @available(iOS 17.0, *)
    public func getProxyConfig() -> ProxyConfiguration? {
        proxy.getProxyConfig()
    }


    // MARK: Private Methods

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
     - returns: A proxy configuration dictionary.
     */
    private static func getSocks5Dict(_ port: Int) -> [AnyHashable: Any] {
        return [
            kCFProxyTypeKey: kCFProxyTypeSOCKS,
            kCFStreamPropertySOCKSVersion: kCFStreamSocketSOCKSVersion5,
            kCFStreamPropertySOCKSProxyHost: "127.0.0.1",
            kCFStreamPropertySOCKSProxyPort: port]
    }

    /**
     Create a valid SOCKS5 proxy configuration object for a localhost server with the given port.

     - parameter port: The port number to use.
     - returns: A ``ProxyConfiguration`` object.
     */
    @available(iOS 17.0, *)
    private static func getSocks5Config(_ port: Int) -> ProxyConfiguration? {
        guard port <= UInt16.max,
              let port = NWEndpoint.Port(rawValue: UInt16(port))
        else {
            return nil
        }

        return ProxyConfiguration(socksv5Proxy: .hostPort(host: .ipv4(.loopback), port: port))
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
}
