//
//  EnvoyUrlProtocol.swift
//  Envoy
//
//  Created by Benjamin Erhart on 18.04.24.
//

import Foundation

/**
 TODO: This is far from finished, it probably doesn't even work because of the missing thread steering.

 As it seems, URLProtocol didn't grow with the capabilities of URLSession.
 This cannot replace direct modification of URLRequests and proxy setup.

 - This will need a single URLSession and demultiplexing.
 - It's probably a bad idea to try to go to the last details of all tasks, instead maybe reduce to data task again.
 - There should be an initializer method to hand over a URLSessionConfiguration.
 - This needs explicit thread steering.
 - This probably also needs a custom delegate to handle the cases, where we cannot replicate the correct behaviour
   due to missing interfaces.

 https://developer.apple.com/library/archive/samplecode/CustomHTTPProtocol/Listings/Read_Me_About_CustomHTTPProtocol_txt.html#//apple_ref/doc/uid/DTS40013653-Read_Me_About_CustomHTTPProtocol_txt-DontLinkElementID_23
 */
class EnvoyUrlProtocolDoNotUse: URLProtocol,
                            URLSessionDelegate, URLSessionTaskDelegate, URLSessionDataDelegate, URLSessionDownloadDelegate,
                            URLAuthenticationChallengeSender
{

    private static let loopDetection = "EnvoyUrlProtocolLoopDetection"

    private var mySession: URLSession?

    private var myTask: URLSessionTask?

    private var pendingAuthCompletion: ((URLSession.AuthChallengeDisposition, URLCredential?) -> Void)?
    private var pendingAuthChallenge: URLAuthenticationChallenge?

    override class func canInit(with task: URLSessionTask) -> Bool {
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

    override class func canonicalRequest(for request: URLRequest) -> URLRequest {
        Envoy.shared.maybeModify(request)
    }

    override func startLoading() {
        let conf = URLSessionConfiguration.default
        conf.connectionProxyDictionary = Envoy.shared.getProxyDict()

        mySession = URLSession(configuration: conf, delegate: self, delegateQueue: nil)

        var request = Envoy.shared.maybeModify(request)

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

    }

    override func stopLoading() {
        myTask?.cancel()
        myTask = nil

        mySession?.invalidateAndCancel()
        mySession = nil
    }


    // MARK: URLSessionDelegate

    func urlSession(_ session: URLSession, didBecomeInvalidWithError error: (any Error)?) {
        if let error = error {
            fail(error)
        }
        else {
            client?.urlProtocolDidFinishLoading(self)
        }
    }

    func urlSession(_ session: URLSession, didReceive challenge: URLAuthenticationChallenge,
                    completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void
    ) {
        switch challenge.protectionSpace.authenticationMethod {
        case NSURLAuthenticationMethodDefault, NSURLAuthenticationMethodHTTPBasic, NSURLAuthenticationMethodHTTPDigest:
            if let challenge = pendingAuthChallenge {
                client?.urlProtocol(self, didCancel: challenge)
                cancel(challenge)
            }
            pendingAuthCompletion = completionHandler
            pendingAuthChallenge = challenge

            client?.urlProtocol(self, didReceive: .init(authenticationChallenge: challenge, sender: self))

        default:
            // TODO: It is said, that we need to implement this ourselves.
            break
        }
    }


    // MARK: URLSessionTaskDelegate

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: (any Error)?) {
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

    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection 
                    response: HTTPURLResponse, newRequest request: URLRequest, 
                    completionHandler: @escaping (URLRequest?) -> Void
    ) {
        client?.urlProtocol(self, wasRedirectedTo: Envoy.shared.revertModification(request), redirectResponse: response)

        completionHandler(Envoy.shared.maybeModify(request))
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didSendBodyData bytesSent: Int64, 
                    totalBytesSent: Int64, totalBytesExpectedToSend: Int64
    ) {
        guard #available(iOS 15.0, *),
              let originalTask = self.task
        else {
            return
        }

        originalTask.delegate?.urlSession?(
            session, task: originalTask, didSendBodyData: bytesSent, totalBytesSent: totalBytesSent,
            totalBytesExpectedToSend: totalBytesExpectedToSend)
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, 
                    needNewBodyStream completionHandler: @escaping (InputStream?) -> Void
    ) {
        guard #available(iOS 15.0, *),
              let originalTask = self.task
        else {
            return
        }

        originalTask.delegate?.urlSession?(session, task: originalTask, needNewBodyStream: completionHandler)
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didReceive challenge: URLAuthenticationChallenge, 
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

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse, 
                    completionHandler: @escaping (URLSession.ResponseDisposition) -> Void
    ) {
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .allowed)

        completionHandler(.allow)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        client?.urlProtocol(self, didLoad: data)
    }


    // MARK: URLSessionDownloadDelegate

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        Task {
            do {
                let data = try Data(contentsOf: location)
                client?.urlProtocol(self, didLoad: data)
            }
            catch {
                client?.urlProtocol(self, didFailWithError: error)
            }
        }
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, 
                    didResumeAtOffset fileOffset: Int64, expectedTotalBytes: Int64)
    {
        guard #available(iOS 15.0, *),
              let originalTask = task as? URLSessionDownloadTask
        else {
            return
        }

        (originalTask.delegate as? URLSessionDownloadDelegate)?.urlSession?(
            session, downloadTask: originalTask, didResumeAtOffset: fileOffset, expectedTotalBytes: expectedTotalBytes)
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, 
                    didWriteData bytesWritten: Int64, totalBytesWritten: Int64, totalBytesExpectedToWrite: Int64
    ) {
        guard #available(iOS 15.0, *),
              let originalTask = task as? URLSessionDownloadTask
        else {
            return
        }

        (originalTask.delegate as? URLSessionDownloadDelegate)?.urlSession?(
            session, downloadTask: originalTask, didWriteData: bytesWritten, totalBytesWritten: totalBytesWritten,
            totalBytesExpectedToWrite: totalBytesExpectedToWrite)
    }


    // MARK: URLAuthenticationChallengeSender

    func use(_ credential: URLCredential, for challenge: URLAuthenticationChallenge) {
        if challenge == pendingAuthChallenge {
            pendingAuthCompletion?(.useCredential, credential)
            pendingAuthCompletion = nil
            pendingAuthChallenge = nil
        }
    }

    func continueWithoutCredential(for challenge: URLAuthenticationChallenge) {
        if challenge == pendingAuthChallenge {
            pendingAuthCompletion?(.performDefaultHandling, nil)
            pendingAuthCompletion = nil
            pendingAuthChallenge = nil
        }
    }

    func cancel(_ challenge: URLAuthenticationChallenge) {
        if challenge == pendingAuthChallenge {
            pendingAuthCompletion?(.cancelAuthenticationChallenge, nil)
            pendingAuthCompletion = nil
            pendingAuthChallenge = nil
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
