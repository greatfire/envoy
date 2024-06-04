//
//  ViewController.swift
//  Envoy
//
//  Created by Benjamin Erhart on 03/21/2024.
//  Copyright (c) 2024 Benjamin Erhart. All rights reserved.
//

import UIKit
import WebKit
import os
import Envoy

class ViewController: UIViewController, UITextFieldDelegate, WKNavigationDelegate {

    @IBOutlet weak var busyView: UIView!

    @IBOutlet weak var addressTf: UITextField!

    private var webView: WKWebView!


    override func viewDidLoad() {
        super.viewDidLoad()

        addressTf.text = "https://www.wikipedia.org"

        busyView.layer.zPosition = 1000

        Task {
            Envoy.ptLogging = true
            print("[\(String(describing: type(of: self)))] ptStateDir=\(Envoy.ptStateDir?.path ?? "(nil)")")

            await Envoy.shared.start(urls: [], testDirect: true)

            print("[\(String(describing: type(of: self)))] selected proxy: \(Envoy.shared.proxy)")

            initWebView()

//            if #available(iOS 17.0, *) {
//                if let proxy = Envoy.shared.getProxyConfig() {
//                    webView.configuration.websiteDataStore.proxyConfigurations.append(proxy)
//                }
//            }

            busyView.isHidden = true

            textFieldDidEndEditing(addressTf, reason: .committed)
        }
    }

    override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)

        Envoy.shared.stop()
    }


    // MARK: UITextFieldDelegate

    func textFieldDidEndEditing(_ textField: UITextField, reason: UITextField.DidEndEditingReason) {
        if reason == .committed,
           let text = addressTf.text?.trimmingCharacters(in: .whitespacesAndNewlines),
           let urlc = URLComponents(string: text)
        {
            if let url = urlc.url {
                addressTf.text = url.absoluteString

//                let conf = URLSessionConfiguration.default
//                conf.protocolClasses = [EnvoyUrlProtocol.self]
//
//                let session = URLSession(configuration: conf)
//
//                Task {
//                    do {
//                        let (data, response) = try await session.data(for: URLRequest(url: url))
//
//                        print("[\(String(describing: type(of: self)))] response=\(response), data=\(String(data: data, encoding: .utf8) ?? "(nil)")")
//                    }
//                    catch {
//                        print("[\(String(describing: type(of: self)))] error=\(error)")
//                    }
//                }

                webView.stopLoading()
                webView.load(EnvoySchemeHandler.modify(URLRequest(url: url)))
            }
        }
    }

    func textFieldShouldReturn(_ textField: UITextField) -> Bool {
        textField.resignFirstResponder()

        return true
    }


    // MARK: WKNavigationDelegate

    func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction, 
                 decisionHandler: @escaping (WKNavigationActionPolicy) -> Void)
    {
        if let text = EnvoySchemeHandler.revertModification(navigationAction.request).url?.absoluteString,
           !text.isEmpty
        {
            addressTf.text = text
        }

        let modified = EnvoySchemeHandler.modify(navigationAction.request)

        // This request doesn't need to get modified or already is. Allow.
        if modified == navigationAction.request {
            return decisionHandler(.allow)
        }

        // This request needs to be modified. Cancel and re-issue.
        decisionHandler(.cancel)

        DispatchQueue.main.async {
            webView.load(modified)
        }
    }

    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: any Error) {
        print("[\(String(describing: type(of: self)))] error=\(error)")
    }


    // MARK: Private Methods

    private func initWebView() {
        webView = WKWebView(frame: .zero, configuration: EnvoySchemeHandler.register())
        webView.navigationDelegate = self
        webView.translatesAutoresizingMaskIntoConstraints = false

        if #available(iOS 16.4, *) {
            webView.isInspectable = true
        }

        view.addSubview(webView)

        webView.topAnchor.constraint(equalTo: addressTf.bottomAnchor, constant: 8).isActive = true
        webView.leadingAnchor.constraint(equalTo: view.leadingAnchor).isActive = true
        webView.trailingAnchor.constraint(equalTo: view.trailingAnchor).isActive = true
        webView.bottomAnchor.constraint(equalTo: view.bottomAnchor).isActive = true
    }
}
