//
//  URLComponent+Helpers.swift
//  Envoy
//
//  Created by Benjamin Erhart on 16.04.24.
//  Copyright © 2024 GreatFire. Licensed under Apache-2.0.
//

import Foundation
import Network

extension URLComponents {

    /**
     Returns the value of the first query item with the given name.

     Will return `nil` if the value is empty, too.

     - parameter name: The name of the query item to search for.
     - returns: The value of the first query item with the given name.
     */
    func firstQueryItem(of name: String) -> String? {
        let value = queryItems?.first(where: { $0.name == name })?.value

        return (value?.isEmpty ?? true) ? nil : value
    }

    /**
     Will append a query item with the given name and value.

     Will create the `queryItems` array, if it doesn't exist, yet.

     - parameter name: The name of the query item.
     - parameter value: The value of the query item.
     */
    mutating func appendQueryItem(_ name: String, _ value: String?) {
        if queryItems == nil {
            queryItems = []
        }

        queryItems?.append(.init(name: name, value: value))
    }

    /**
     Remove all query items with a given name.

     Will also remove the `queryItems` array, if it becomes empty, to remove the  "?" piece from the URL.

     - parameter name: The name of the query item.
     */
    mutating func removeQueryItems(named name: String) {
        queryItems?.removeAll(where: { $0.name == name })

        if queryItems?.isEmpty ?? true {
            queryItems = nil // Remove `?` separator.
        }
    }
}

extension URL {

    /**
     Constructs a  ``URLComponents`` object from itself.
     */
    var urlc: URLComponents? {
        URLComponents(url: self, resolvingAgainstBaseURL: false)
    }

    /**
     Proxy for ``URLComponents/queryItems``.
     */
    var queryItems: [URLQueryItem] {
        urlc?.queryItems ?? []
    }

    /**
     Proxy for ``URLComponents/firstQueryItem(of:)``.

     Will return `nil` if the value is empty, too.

     - parameter name: The name of the query item.
     - returns: The value of the first query item with the given name.
     */
    func firstQueryItem(of name: String) -> String? {
        urlc?.firstQueryItem(of: name)
    }

    /**
     Proxy for  ``URLComponents/appendQueryItem(_:_:)``.

     Will create the `queryItems` array, if it doesn't exist, yet.

     - parameter name: The name of the query item.
     - parameter value: The value of the query item.
     - returns: A copy of `self` containing the new query item, or `self` if that couldn't be constructed.
     */
    func appendingQueryItem(_ name: String, _ value: String?) -> URL {
        var urlc = urlc
        urlc?.appendQueryItem(name, value)

        return urlc?.url ?? self
    }

    /**
     Proxy for ``URLComponents/removeQueryItems(named:)``.

     Will also remove the `queryItems` array, if it becomes empty, to remove the  "?" piece from the URL.

     - parameter name: The name of the query item.
     - returns: A copy of `self` without the respective query items, or `self` if that couldn't be constructed.
     */
    func removingQueryItems(named name: String) -> URL {
        var urlc = urlc
        urlc?.removeQueryItems(named: name)

        return urlc?.url ?? self
    }

    /**
     - parameter scheme: The new scheme to use.
     - returns A copy of `self` with the scheme changed or `self` if that couldn't be constructed.
     */
    func settingScheme(_ scheme: String) -> URL {
        var urlc = urlc
        urlc?.scheme = scheme

        return urlc?.url ?? self
    }

    var proxyDict: [AnyHashable: Any]? {
        guard let scheme = scheme?.lowercased(),
              scheme == "socks4" || scheme == "socks5" || scheme == "http" || scheme == "https",
              let host = host, !host.isEmpty,
              let port = port
        else {
            return nil
        }

        var dict: [AnyHashable: Any] = [
            kCFStreamPropertySOCKSProxyHost: host,
            kCFStreamPropertySOCKSProxyPort: port]

        var isSocks = false

        switch scheme {
        case "socks4":
            dict[kCFProxyTypeKey] = kCFProxyTypeSOCKS
            dict[kCFStreamPropertySOCKSVersion] = kCFStreamSocketSOCKSVersion4
            isSocks = true

        case "socks5":
            dict[kCFProxyTypeKey] = kCFProxyTypeSOCKS
            dict[kCFStreamPropertySOCKSVersion] = kCFStreamSocketSOCKSVersion5
            isSocks = true

        case "http":
            dict[kCFProxyTypeKey] = kCFProxyTypeHTTP

        case "https":
            dict[kCFProxyTypeKey] = kCFProxyTypeHTTPS

        default:
            return nil
        }

        if let user = user, !user.isEmpty {
            dict[isSocks ? kCFStreamPropertySOCKSUser : kCFHTTPAuthenticationUsername] = user
        }

        if let password = password, !password.isEmpty {
            dict[isSocks ? kCFStreamPropertySOCKSPassword : kCFHTTPAuthenticationPassword] = password
        }

        return dict
    }

    @available(iOS 17.0, macOS 14.0, *)
    var proxyConf: ProxyConfiguration? {
        guard let scheme = scheme?.lowercased(),
              scheme == "socks5" || scheme == "http" || scheme == "https",
              let host = host, !host.isEmpty,
              let port = port,
              let port = NWEndpoint.Port(rawValue: UInt16(port))
        else {
            return nil
        }

        let endpoint = NWEndpoint.hostPort(host: .init(host), port: port)

        let conf: ProxyConfiguration

        if scheme == "socks5" {
            conf = .init(socksv5Proxy: endpoint)
        }
        else {
            conf = .init(httpCONNECTProxy: endpoint, tlsOptions: scheme == "http" ? nil : .init())
        }

        if let user = user, !user.isEmpty {
            conf.applyCredential(username: user, password: password ?? "")
        }
        else if let password = password, !password.isEmpty {
            conf.applyCredential(username: user ?? "", password: password)
        }

        return conf
    }
}
