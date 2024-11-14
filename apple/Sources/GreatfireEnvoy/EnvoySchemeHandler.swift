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

public class EnvoySchemeHandler: NSObject, WKURLSchemeHandler, URLSessionDataDelegate {

    private static let httpScheme = "envoy-http"
    private static let httpsScheme = "envoy-https"

#if USE_CURL
    private var tasks = [Int : CurlTask]()
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
        Task {
            do {
                let (data, response) = try await perform(request, urlSchemeTask.hash)

                if tasks[urlSchemeTask.hash]?.state == .completed {
                    urlSchemeTask.didReceive(response)
                    urlSchemeTask.didReceive(data)
                    urlSchemeTask.didFinish()
                }
            }
            catch {
                if (error as NSError).domain != NSURLErrorDomain || (error as NSError).code != NSURLErrorCancelled {
                    urlSchemeTask.didFailWithError(error)
                }
            }

            tasks[urlSchemeTask.hash] = nil
        }
#else
        let task = session.dataTask(with: Envoy.shared.maybeModify(request).request)
        tasks[task] = urlSchemeTask
        task.resume()
#endif
    }

    public func webView(_ webView: WKWebView, stop urlSchemeTask: any WKURLSchemeTask) {
        Envoy.log("task=\(urlSchemeTask)", self)

#if USE_CURL
        if let task = tasks[urlSchemeTask.hash] {
            task.cancel()
        }
#else
        if let task = tasks.first(where: { $1.hash == urlSchemeTask.hash })?.key {
            task.cancel()

            tasks[task] = nil
        }
#endif
    }


#if USE_CURL
    /**
     Implements our own redirect logic, so we can inject the proxy again on redirection.
     */
    func perform(_ request: URLRequest, _ wkTaskHash: Int, _ counter: UInt = 0) async throws -> (data: Data, response: URLResponse) {
        guard let task = Envoy.shared.task(from: SwiftyCurl.shared, with: request) else {
            throw NSError(domain: NSURLErrorDomain, code: -1, userInfo: [NSLocalizedDescriptionKey: "Could not create curl."])
        }

        tasks[wkTaskHash] = task

        let (data, response) = try await task.resume()

        if counter < 50,
           let hr = response as? HTTPURLResponse,
           hr.statusCode >= 300 && hr.statusCode < 400, // Server tells us to redirect
           let location = hr.value(forHTTPHeaderField: "Location"),
           let location = URL(string: location)
        {
            var redirect = request
            redirect.url = location

            if hr.statusCode == 302 || hr.statusCode == 303 {
                redirect.httpMethod = "GET"
            }

            return try await perform(redirect, wkTaskHash, counter + 1)
        }

        return (data, response)
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
