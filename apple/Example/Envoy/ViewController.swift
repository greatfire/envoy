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

    @IBOutlet weak var addressTf: UITextField!

    private var webView: WKWebView!


    override func viewDidLoad() {
        super.viewDidLoad()

        addressTf.text = "https://www.wikipedia.org"

        Envoy.shared.initialize(urls: [])

        Envoy.shared.startProxy()

        let conf = WKWebViewConfiguration()

        if #available(iOS 17.0, *) {
            if let proxy = Envoy.shared.getProxyConfig() {
                conf.websiteDataStore.proxyConfigurations.append(proxy)
            }
        }

        webView = WKWebView(frame: .zero, configuration: conf)
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

        textFieldDidEndEditing(addressTf, reason: .committed)
    }

    override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)

        Envoy.shared.stopProxy()
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
                webView.load(Envoy.shared.maybeModify(URLRequest(url: url)))
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
        let modified = Envoy.shared.maybeModify(navigationAction.request)

        // This request doesn't need to get modified or already is. Allow.
        if modified == navigationAction.request {
            return decisionHandler(.allow)
        }

        if let text = navigationAction.request.url?.absoluteString, !text.isEmpty {
            addressTf.text = text
        }

        // This request needs to be modified. Cancel and re-issue.
        decisionHandler(.cancel)

        DispatchQueue.main.async {
            webView.load(modified)
        }
    }
}
