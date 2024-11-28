//
//  EnvoySchemeHandler.swift
//  Envoy
//
//  Created by Benjamin Erhart on 8.05.24.
//  Copyright © 2024 GreatFire. Licensed under Apache-2.0.
//

import Foundation
import WebKit

#if USE_CURL
import SwiftyCurl
#endif

public class EnvoySchemeHandler: NSObject, WKURLSchemeHandler, URLSessionDataDelegate, CurlTaskDelegate {

    private static let httpScheme = "envoy-http"
    private static let httpsScheme = "envoy-https"

#if USE_CURL
    private var tasks = [CurlTask: WKURLSchemeTask]()
#else
    private var session: URLSession!

    private var tasks = [URLSessionTask: WKURLSchemeTask]()
#endif


    private init(_ conf: URLSessionConfiguration = .default) {
        super.init()

#if !USE_CURL
        conf.connectionProxyDictionary = Envoy.shared.getProxyDict()

        session = URLSession(configuration: conf, delegate: self, delegateQueue: nil)
#endif
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
        let request = Self.revertModification(urlSchemeTask.request)

        Envoy.log("\(request.url?.absoluteString ?? "(nil)")", self)

#if USE_CURL
        guard let task = Envoy.shared.task(from: SwiftyCurl.shared, with: request) else {
            urlSchemeTask.didFailWithError(NSError(domain: NSURLErrorDomain, code: -1, userInfo: [NSLocalizedDescriptionKey: "Could not instantiate a `CurlTask` object!"]))
            return
        }

        task.delegate = self
        DispatchQueue.global(qos: .userInitiated).sync {
            tasks[task] = urlSchemeTask
        }
        task.resume()
#else
        let task = session.dataTask(with: Envoy.shared.maybeModify(request).request)
        tasks[task] = urlSchemeTask
        task.resume()
#endif
    }

    public func webView(_ webView: WKWebView, stop urlSchemeTask: any WKURLSchemeTask) {
        Envoy.log("task=\(urlSchemeTask)", self)

        if let task = tasks.first(where: { $1.hash == urlSchemeTask.hash })?.key {
            task.cancel()

            tasks[task] = nil
        }
    }


#if USE_CURL

    // MARK: CurlTaskDelegate

    public func task(_ task: CurlTask, isHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest) {
        // Don't receive further interfering callbacks from this task.
        task.delegate = nil

        print("Redirect: \(response.statusCode) \(response.url?.absoluteString ?? "(nil)") -> \(request.httpMethod ?? "(nil)") \(request.url?.absoluteString ?? "(nil)")")

        // Issue a new task with the redirected URL.
        guard let newTask = Envoy.shared.task(from: SwiftyCurl.shared, with: request) else {
            DispatchQueue.global(qos: .userInitiated).sync {
                tasks[task]?.didFailWithError(NSError(
                    domain: NSURLErrorDomain, code: -1,
                    userInfo: [NSLocalizedDescriptionKey: "Could not instantiate a `CurlTask` object!"]))
            }

            return
        }

        newTask.delegate = self

        DispatchQueue.global(qos: .userInitiated).sync {
            tasks[newTask] = tasks[task]
            tasks[task] = nil
        }

        newTask.resume()
    }

    public func task(_ task: CurlTask, didReceive response: URLResponse) -> Bool {
        DispatchQueue.global(qos: .userInitiated).sync {
            tasks[task]?.didReceive(response)
        }

        return true
    }

    public func task(_ task: CurlTask, didReceive data: Data) -> Bool {
        DispatchQueue.global(qos: .userInitiated).sync {
            tasks[task]?.didReceive(data)
        }

        return true
    }

    public func task(_ task: CurlTask, didCompleteWithError error: (any Error)?) {
        DispatchQueue.global(qos: .userInitiated).sync {
            if let error = error {
                Envoy.log(error, self)

                tasks[task]?.didFailWithError(error)
            }
            else {
                tasks[task]?.didFinish()
            }

            tasks[task] = nil
        }
    }


#else
    // MARK: URLSessionTaskDelegate

    public func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: (any Error)?) {
        if let error = error {
            Envoy.log(error, self)

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
#endif
}
