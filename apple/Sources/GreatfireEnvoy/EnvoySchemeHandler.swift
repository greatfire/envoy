//
//  EnvoySchemeHandler.swift
//  Envoy
//
//  Created by Benjamin Erhart on 8.05.24.
//  Copyright © 2024 GreatFire. Licensed under Apache-2.0.
//

import Foundation
import WebKit

public class EnvoySchemeHandler: NSObject, WKURLSchemeHandler, URLSessionDataDelegate {

    private static let httpScheme = "envoy-http"
    private static let httpsScheme = "envoy-https"

    private var session: URLSession!

    private var tasks = [URLSessionTask: WKURLSchemeTask]()


    private init(_ conf: URLSessionConfiguration = .default) {
        super.init()

        conf.connectionProxyDictionary = Envoy.shared.getProxyDict()

        session = URLSession(configuration: conf, delegate: self, delegateQueue: nil)
    }


    // MARK: Class Methods

    public class func register(
        _ conf: WKWebViewConfiguration = .init(),
        urlSessionConf: URLSessionConfiguration = .default
    ) -> WKWebViewConfiguration {
        let handler = EnvoySchemeHandler(urlSessionConf)

        conf.setURLSchemeHandler(handler, forURLScheme: httpScheme)
        conf.setURLSchemeHandler(handler, forURLScheme: httpsScheme)

        return conf
    }

    public class func modify(_ request: URLRequest) -> URLRequest {
        var modified = request

        if (modified.url?.scheme?.isEmpty ?? true) || modified.url?.scheme?.caseInsensitiveCompare("https") == .orderedSame {
            modified.url = modified.url?.settingScheme(httpsScheme)
        }
        else if modified.url?.scheme?.caseInsensitiveCompare("http") == .orderedSame {
            modified.url = modified.url?.settingScheme(httpScheme)
        }

        return modified
    }

    public class func revertModification(_ request: URLRequest) -> URLRequest {
        switch request.url?.scheme {
        case httpScheme:
            var modified = request
            modified.url = modified.url?.settingScheme("http")

            return modified

        case httpsScheme:
            var modified = request
            modified.url = modified.url?.settingScheme("https")

            return modified

        default:
            return request
        }
    }


    // MARK: WKURLSchemeHandler

    public func webView(_ webView: WKWebView, start urlSchemeTask: any WKURLSchemeTask) {
        let request = Envoy.shared.maybeModify(Self.revertModification(urlSchemeTask.request))

        print("[\(String(describing: type(of: self)))] \(request.url?.absoluteString ?? "(nil)"): \(request.value(forHTTPHeaderField: "Url-Orig") ?? "(nil)")")

        let task = session.dataTask(with: request)
        tasks[task] = urlSchemeTask
        task.resume()
    }

    public func webView(_ webView: WKWebView, stop urlSchemeTask: any WKURLSchemeTask) {
        print("[\(String(describing: type(of: self)))]#stop task=\(urlSchemeTask)")

        if let task = tasks.first(where: { $1.hash == urlSchemeTask.hash })?.key {
            task.cancel()

            tasks[task] = nil
        }
    }


    // MARK: URLSessionTaskDelegate

    public func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: (any Error)?) {
        if let error = error {
            print("[\(String(describing: type(of: self)))]#didCompleteWithError \(error)")

            tasks[task]?.didFailWithError(error)
        }
        else {
            tasks[task]?.didFinish()
        }

        tasks[task] = nil
    }


    // MARK: URLSessionDataDelegate

    public func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse) async -> URLSession.ResponseDisposition {
        tasks[dataTask]?.didReceive(response)

        return .allow
    }

    public func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        tasks[dataTask]?.didReceive(data)
    }
}
