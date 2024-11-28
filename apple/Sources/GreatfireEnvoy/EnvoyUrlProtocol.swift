//
//  EnvoyUrlProtocol.swift
//  Envoy
//
//  Created by Benjamin Erhart on 18.04.24.
//  Copyright © 2024 GreatFire. Licensed under Apache-2.0.
//

import Foundation

#if USE_CURL
import SwiftyCurl
#endif

/**
 TODO: This is far from finished, it might not even properly work because of the missing thread steering.

 As it seems, `URLProtocol` didn't grow with the capabilities of `URLSession`.
 This cannot replace direct modification of `URLRequest`s and proxy setup.

 - This will need a single `URLSession` and demultiplexing.
 - It's probably a bad idea to try to go to the last details of all tasks, instead maybe reduce to data task again.
 - There should be an initializer method to hand over a `URLSessionConfiguration`.
 - This needs explicit thread steering.
 - This probably also needs a custom delegate to handle the cases, where we cannot replicate the correct behaviour
   due to missing interfaces.

 https://developer.apple.com/library/archive/samplecode/CustomHTTPProtocol/Listings/Read_Me_About_CustomHTTPProtocol_txt.html#//apple_ref/doc/uid/DTS40013653-Read_Me_About_CustomHTTPProtocol_txt-DontLinkElementID_23
 */
public class EnvoyUrlProtocol: URLProtocol,
                            URLSessionDelegate, URLSessionTaskDelegate, URLSessionDataDelegate, URLSessionDownloadDelegate,
                               URLAuthenticationChallengeSender, CurlTaskDelegate
{

    private static let loopDetection = "EnvoyUrlProtocolLoopDetection"

#if USE_CURL
    private var myTask: CurlTask?
#else
    public static var conf = URLSessionConfiguration.default

    private var mySession: URLSession?

    private var myTask: URLSessionTask?
#endif

    private var pendingAuthCompletion: ((URLSession.AuthChallengeDisposition, URLCredential?) -> Void)?
    private var pendingAuthChallenge: URLAuthenticationChallenge?

    override public class func canInit(with task: URLSessionTask) -> Bool {
        guard Envoy.shared.proxy != .direct,
              let request = task.originalRequest ?? task.currentRequest,
              Self.property(forKey: loopDetection, in: request) as? Bool != true
        else {
            return false
        }

        switch request.url?.scheme?.lowercased() {
        case "http", "https":
            return true

        default:
            return false
        }
    }

    override public class func canonicalRequest(for request: URLRequest) -> URLRequest {
        Envoy.shared.maybeModify(request).request
    }

    override public func startLoading() {
#if USE_CURL
        myTask = Envoy.shared.task(from: SwiftyCurl.shared, with: request)
        myTask?.delegate = self

        // Response, body and error handled in delegate callbacks.
        myTask?.resume()
#else
        Self.conf.connectionProxyDictionary = Envoy.shared.getProxyDict()

        mySession = URLSession(configuration: Self.conf, delegate: self, delegateQueue: nil)

        var request = Envoy.shared.maybeModify(request).request

        let mutableRequest = (request as NSURLRequest).mutableCopy() as! NSMutableURLRequest
        Self.setProperty(true, forKey: Self.loopDetection, in: mutableRequest)

        request = mutableRequest as URLRequest

        if task is URLSessionDownloadTask {
            myTask = mySession?.downloadTask(with: request)
        }
        if let task = task as? URLSessionUploadTask {
            if request.httpBodyStream != nil {
                myTask = mySession?.uploadTask(withStreamedRequest: request)
            }
            else if let body = request.httpBody {
                myTask = mySession?.uploadTask(with: request, from: body)
            }
            else {
                // TODO: Where to get the file argument from? Passthru is *not* great.
                myTask = task
            }
        }
        else if task is URLSessionWebSocketTask {
            if let url = request.url {
                myTask = mySession?.webSocketTask(with: url)
            }
            else {
                // Last resort: pass thru.
                myTask = task
            }
        }
        else if task is URLSessionStreamTask {
            if let url = request.url,
               let host = url.host,
               let port = url.port
            {
                myTask = mySession?.streamTask(withHostName: host, port: port)
            }
            else {
                // Last resort: pass thru.
                myTask = task
            }
        }
        else {
            myTask = mySession?.dataTask(with: request)
        }

        myTask?.resume()
#endif
    }

    override public func stopLoading() {
        myTask?.cancel()
        myTask = nil

#if !USE_CURL
        mySession?.invalidateAndCancel()
        mySession = nil
#endif
    }


    // MARK: URLSessionDelegate

    public func urlSession(_ session: URLSession, didBecomeInvalidWithError error: (any Error)?) {
        if let error = error {
            fail(error)
        }
        else {
            client?.urlProtocolDidFinishLoading(self)
        }
    }

    public func urlSession(_ session: URLSession, didReceive challenge: URLAuthenticationChallenge,
                           completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void
    ) {
        if let challenge = pendingAuthChallenge {
            client?.urlProtocol(self, didCancel: challenge)
            cancel(challenge)
        }
        pendingAuthCompletion = completionHandler
        pendingAuthChallenge = .init(authenticationChallenge: challenge, sender: self)

        client?.urlProtocol(self, didReceive: pendingAuthChallenge!)
    }


    // MARK: URLSessionTaskDelegate

#if !USE_CURL
    public func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: (any Error)?) {
        if task == myTask {
            myTask = nil
            mySession?.invalidateAndCancel()
        }

        if let error = error {
            fail(error)
        }
        else {
            client?.urlProtocolDidFinishLoading(self)
        }
    }
#endif

    public func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection
                           response: HTTPURLResponse, newRequest request: URLRequest,
                           completionHandler: @escaping (URLRequest?) -> Void
    ) {
        client?.urlProtocol(self, wasRedirectedTo: Envoy.shared.revertModification(request), redirectResponse: response)

        completionHandler(Envoy.shared.maybeModify(request).request)
    }

    public func urlSession(_ session: URLSession, task: URLSessionTask, didSendBodyData bytesSent: Int64,
                           totalBytesSent: Int64, totalBytesExpectedToSend: Int64
    ) {
        guard #available(iOS 15.0, macOS 12.0, *),
              let originalTask = self.task
        else {
            return
        }

        originalTask.delegate?.urlSession?(
            session, task: originalTask, didSendBodyData: bytesSent, totalBytesSent: totalBytesSent,
            totalBytesExpectedToSend: totalBytesExpectedToSend)
    }

    public func urlSession(_ session: URLSession, task: URLSessionTask, 
                           needNewBodyStream completionHandler: @escaping (InputStream?) -> Void
    ) {
        guard #available(iOS 15.0, macOS 12.0, *),
              let originalTask = self.task
        else {
            return
        }

        originalTask.delegate?.urlSession?(session, task: originalTask, needNewBodyStream: completionHandler)
    }

    public func urlSession(_ session: URLSession, task: URLSessionTask, didReceive challenge: URLAuthenticationChallenge, 
                           completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void
    ) {
        if let challenge = pendingAuthChallenge {
            client?.urlProtocol(self, didCancel: challenge)
            cancel(challenge)
        }

        pendingAuthCompletion = completionHandler
        pendingAuthChallenge = challenge

        client?.urlProtocol(self, didReceive: .init(authenticationChallenge: challenge, sender: self))
    }


    // MARK: URLSessionDataDelegate

    public func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse, 
                           completionHandler: @escaping (URLSession.ResponseDisposition) -> Void
    ) {
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .allowed)

        completionHandler(.allow)
    }

    public func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        client?.urlProtocol(self, didLoad: data)
    }


    // MARK: URLSessionDownloadDelegate

    public func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        Task {
            do {
                let data = try Data(contentsOf: location)
                client?.urlProtocol(self, didLoad: data)
            }
            catch {
                fail(error)
            }
        }
    }

    public func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, 
                           didResumeAtOffset fileOffset: Int64, expectedTotalBytes: Int64)
    {
        guard #available(iOS 15.0, macOS 12.0, *),
              let originalTask = task as? URLSessionDownloadTask
        else {
            return
        }

        (originalTask.delegate as? URLSessionDownloadDelegate)?.urlSession?(
            session, downloadTask: originalTask, didResumeAtOffset: fileOffset, expectedTotalBytes: expectedTotalBytes)
    }

    public func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask,
                           didWriteData bytesWritten: Int64, totalBytesWritten: Int64, totalBytesExpectedToWrite: Int64
    ) {
        guard #available(iOS 15.0, macOS 12.0, *),
              let originalTask = task as? URLSessionDownloadTask
        else {
            return
        }

        (originalTask.delegate as? URLSessionDownloadDelegate)?.urlSession?(
            session, downloadTask: originalTask, didWriteData: bytesWritten, totalBytesWritten: totalBytesWritten,
            totalBytesExpectedToWrite: totalBytesExpectedToWrite)
    }


    // MARK: URLAuthenticationChallengeSender

    public func use(_ credential: URLCredential, for challenge: URLAuthenticationChallenge) {
        if challenge == pendingAuthChallenge {
            pendingAuthCompletion?(.useCredential, credential)
            pendingAuthCompletion = nil
            pendingAuthChallenge = nil
        }
    }

    public func continueWithoutCredential(for challenge: URLAuthenticationChallenge) {
        if challenge == pendingAuthChallenge {
            pendingAuthCompletion?(.performDefaultHandling, nil)
            pendingAuthCompletion = nil
            pendingAuthChallenge = nil
        }
    }

    public func cancel(_ challenge: URLAuthenticationChallenge) {
        if challenge == pendingAuthChallenge {
            pendingAuthCompletion?(.cancelAuthenticationChallenge, nil)
            pendingAuthCompletion = nil
            pendingAuthChallenge = nil
        }
    }

    public func performDefaultHandling(for challenge: URLAuthenticationChallenge) {
        if challenge == pendingAuthChallenge {
            pendingAuthCompletion?(.performDefaultHandling, nil)
            pendingAuthCompletion = nil
            pendingAuthChallenge = nil
        }
    }

    public func rejectProtectionSpaceAndContinue(with challenge: URLAuthenticationChallenge) {
        if challenge == pendingAuthChallenge {
            pendingAuthCompletion?(.rejectProtectionSpace, nil)
            pendingAuthCompletion = nil
            pendingAuthChallenge = nil
        }
    }


    // MARK: CurlTaskDelegate

    public func task(_ task: CurlTask, isHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest) {
        client?.urlProtocol(self, wasRedirectedTo: request, redirectResponse: response)

        // Don't receive further interfering callbacks from this task.
        myTask?.delegate = nil

        // Issue a new task with the redirected URL.
        myTask = Envoy.shared.task(from: SwiftyCurl.shared, with: request)
        myTask?.delegate = self
        myTask?.resume()
    }

    public func task(_ task: CurlTask, didReceive challenge: URLAuthenticationChallenge) -> Bool {
        if let challenge = pendingAuthChallenge {
            client?.urlProtocol(self, didCancel: challenge)
            cancel(challenge)
        }
        pendingAuthChallenge = .init(authenticationChallenge: challenge, sender: self)

        client?.urlProtocol(self, didReceive: pendingAuthChallenge!)

        return true
    }

    public func task(_ task: CurlTask, didReceive response: URLResponse) -> Bool {
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .allowed)

        return true
    }

    public func task(_ task: CurlTask, didReceive data: Data) -> Bool {
        client?.urlProtocol(self, didLoad: data)

        return true
    }

    public func task(_ task: CurlTask, didCompleteWithError error: (any Error)?) {
        myTask = nil

        if let error = error {
            fail(error)
        }
        else {
            client?.urlProtocolDidFinishLoading(self)
        }
    }


    // MARK: Private Methods

    private func fail(_ error: Error) {
        if let challenge = pendingAuthChallenge {
            client?.urlProtocol(self, didCancel: challenge)
            cancel(challenge)
        }

        client?.urlProtocol(self, didFailWithError: error)
    }
}
