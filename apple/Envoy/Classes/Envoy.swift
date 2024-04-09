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

public class Envoy {

    public enum Proxy: CustomStringConvertible {
        case direct
        case envoy(url: URL, headers: [String: String], salt: String?)
        case v2Ray
        case meek
        case obfs4
        case snowflake

        public var description: String {
            switch self {
            case .direct:
                return "direct"

            case .envoy(let url, let headers, let salt):
                return "envoy url=\(url), headers=\(headers), salt=\(salt ?? "(nil)")"

            case .v2Ray:
                return "v2ray"

            case .meek:
                return "meek"

            case .obfs4:
                return "obfs4"

            case .snowflake:
                return "snowflake"
            }
        }

        public func maybeModify(_ request: URLRequest) -> URLRequest {
            guard case .envoy(var url, let headers, let salt) = self,
                  !(request.url?.absoluteString.hasPrefix(url.absoluteString) ?? false)
            else {
                return request
            }

            switch request.httpMethod {
            case "GET", "HEAD":
                if let digest = digest(request.url, salt) {
                    var urlc = URLComponents(url: url, resolvingAgainstBaseURL: false)
                    if urlc?.queryItems == nil {
                        urlc?.queryItems = []
                    }

                    urlc?.queryItems?.append(.init(name: "_digest", value: digest))
                    url = urlc?.url ?? url
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
                var urlc = URLComponents(url: url, resolvingAgainstBaseURL: false)
                urlc?.queryItems?.removeAll(where: { $0.name == "_digest" })
                if urlc?.queryItems?.isEmpty ?? true {
                    urlc?.queryItems = nil // Remove `?` separator.
                }

                modified.setValue(urlc?.string ?? url.absoluteString, forHTTPHeaderField: "Url-Orig")
            }

            return modified
        }

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

        public func getProxyDict() -> [AnyHashable: Any]? {
            switch self {
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

        @available(iOS 17.0, *)
        public func getProxyConfig() -> ProxyConfiguration? {
            switch self {
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

    public func initialize(urls: [URL], testUrl: URL = URL(string: "https://www.google.com/generate_204")!) {
        var candidates = [Proxy]()

        for url in urls {
            if url.scheme == "http" || url.scheme == "https" {
                candidates.append(.envoy(url: url, headers: [:], salt: nil))
            }
            else if url.scheme == "envoy" {
                let urlc = URLComponents(url: url, resolvingAgainstBaseURL: false)
                var dest: URLComponents? = nil
                var address: String? = nil
                var headers = [String: String]()
                var salt: String? = nil

                for item in urlc?.queryItems ?? [] {
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

    public func maybeModify(_ request: URLRequest) -> URLRequest {
        proxy.maybeModify(request)
    }

    public func revertModification(_ request: URLRequest) -> URLRequest {
        proxy.revertModification(request)
    }

    @available(iOS 17.0, *)
    public func getProxyConfig() -> ProxyConfiguration? {
        proxy.getProxyConfig()
    }


    // MARK: Private Methods

    private static func digest(_ url: URL?, _ salt: String?) -> String? {
        guard let url = url,
              let salt = salt ?? generateRandomString(count: 16),
              let data = (url.absoluteString + salt).data(using: .utf8)
        else {
            return nil
        }

        return SHA256.hash(data: data).map({ String(format: "%02x", $0) }).joined()
    }

    private static func generateRandomString(count: Int) -> String? {
        var bytes = [UInt8](repeating: 0, count: count)
        let result = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)

        guard result == errSecSuccess else {
            return nil
        }

        return Data(bytes).base64EncodedString()
    }

    @available(iOS 17.0, *)
    private static func getSocks5Config(_ port: Int) -> ProxyConfiguration? {
        guard port <= UInt16.max,
              let port = NWEndpoint.Port(rawValue: UInt16(port))
        else {
            return nil
        }

        return ProxyConfiguration(socksv5Proxy: .hostPort(host: .ipv4(.loopback), port: port))
    }

    private static func getSocks5Dict(_ port: Int) -> [AnyHashable: Any] {
        return [
            kCFProxyTypeKey: kCFProxyTypeSOCKS,
            kCFStreamPropertySOCKSVersion: kCFStreamSocketSOCKSVersion5,
            kCFStreamPropertySOCKSProxyHost: "127.0.0.1",
            kCFStreamPropertySOCKSProxyPort: port]
    }
}
