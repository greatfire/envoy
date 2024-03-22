//
//  ViewController.swift
//  Envoy
//
//  Created by Benjamin Erhart on 03/21/2024.
//  Copyright (c) 2024 Benjamin Erhart. All rights reserved.
//

import UIKit
import WebKit
import NIOPosix
import NIOCore
import NIOHTTP1
import os

class ViewController: UIViewController, UITextFieldDelegate, WKNavigationDelegate {

    @IBOutlet weak var addressTf: UITextField!

    private var webView: WKWebView!


    override func viewDidLoad() {
        super.viewDidLoad()

        addressTf.text = "https://www.wikipedia.org"

        let conf = WKWebViewConfiguration()

        if #available(iOS 17.0, *) {
            startProxy()

            let host = NWEndpoint.Host.ipv4(.loopback)
            let port = NWEndpoint.Port(rawValue: 8080)!
            let endpoint = NWEndpoint.hostPort(host: host, port: port)

            let pConf = ProxyConfiguration(httpCONNECTProxy: endpoint)

            conf.websiteDataStore.proxyConfigurations.append(pConf)
        }

        webView = WKWebView(frame: .zero, configuration: conf)
        webView.navigationDelegate = self
        webView.translatesAutoresizingMaskIntoConstraints = false

        view.addSubview(webView)

        webView.topAnchor.constraint(equalTo: addressTf.bottomAnchor, constant: 8).isActive = true
        webView.leadingAnchor.constraint(equalTo: view.leadingAnchor).isActive = true
        webView.trailingAnchor.constraint(equalTo: view.trailingAnchor).isActive = true
        webView.bottomAnchor.constraint(equalTo: view.bottomAnchor).isActive = true

        textFieldDidEndEditing(addressTf, reason: .committed)
    }


    // MARK: UITextFieldDelegate

    func textFieldDidEndEditing(_ textField: UITextField, reason: UITextField.DidEndEditingReason) {
        if reason == .committed,
           let text = addressTf.text?.trimmingCharacters(in: .whitespacesAndNewlines),
           var urlc = URLComponents(string: text)
        {
            if urlc.scheme?.isEmpty ?? true {
                urlc.scheme = "https"
            }

            if let url = urlc.url {
                addressTf.text = url.absoluteString

                webView.stopLoading()
                webView.load(URLRequest(url: url))
            }
        }
    }

    func textFieldShouldReturn(_ textField: UITextField) -> Bool {
        textField.resignFirstResponder()

        return true
    }


    // MARK: WKNavigationDelegate

    func webView(_ webView: WKWebView, didReceiveServerRedirectForProvisionalNavigation navigation: WKNavigation!) {
        if let text = webView.url?.absoluteString, !text.isEmpty {
            addressTf.text = text
        }
    }


    // MARK: Private Methods

    private func startProxy() {
        let group = MultiThreadedEventLoopGroup(numberOfThreads: System.coreCount)

        let bootstrap = ServerBootstrap(group: group)
            .serverChannelOption(ChannelOptions.socket(SOL_SOCKET, SO_REUSEADDR), value: 1)
            .childChannelOption(ChannelOptions.socket(SOL_SOCKET, SO_REUSEADDR), value: 1)
            .childChannelInitializer { channel in
                channel.pipeline.addHandler(ByteToMessageHandler(HTTPRequestDecoder(leftOverBytesStrategy: .forwardBytes)))
                    .flatMap { channel.pipeline.addHandler(HTTPResponseEncoder()) }
                    .flatMap { 
                        channel.pipeline.addHandler(ConnectHandler(
                            logger: Logger(subsystem: Bundle.main.bundleIdentifier!, category: String(describing: ConnectHandler.self))))
                    }
            }

        bootstrap.bind(to: try! SocketAddress(ipAddress: "127.0.0.1", port: 8080)).whenComplete { result in
            switch result {
            case .success(let channel):
                print("Listening on \(channel.localAddress?.description ?? "(nil)")")

            case .failure(let error):
                print("Failed to bind 127.0.0.1:8080: \(error)")
            }
        }

        bootstrap.bind(to: try! SocketAddress(ipAddress: "::1", port: 8080)).whenComplete { result in
            switch result {
            case .success(let channel):
                print("Listening on \(channel.localAddress?.description ?? "(nil)")")

            case .failure(let error):
                print("Failed to bind [::1]:8080: \(error)")
            }
        }
    }
}
