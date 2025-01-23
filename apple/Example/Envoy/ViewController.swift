//
//  ViewController.swift
//  Envoy
//
//  Created by Benjamin Erhart on 03/21/2024.
//  Copyright © 2024 GreatFire. Licensed under Apache-2.0.
//

import UIKit
@preconcurrency import WebKit
import OSLog
import GreatfireEnvoy

class ViewController: UIViewController, UITextFieldDelegate, WKNavigationDelegate {

    @IBOutlet weak var busyView: UIView!

    @IBOutlet weak var addressTf: UITextField!

    private var webView: EnvoyWebView!

    private let log = Logger(for: ViewController.self)


    override func viewDidLoad() {
        super.viewDidLoad()

        addressTf.text = "https://www.wikipedia.org"

        busyView.layer.zPosition = 1000

        Task {
            Envoy.ptLogging = true
            log.debug("ptStateDir=\(Envoy.ptStateDir?.path ?? "(nil)")")

            let proxies = Proxy.fetch()
            log.debug("proxies=\(proxies)")

            await Envoy.shared.start(urls: proxies.map({ $0.url }), testDirect: proxies.isEmpty)

            log.debug("selected proxy: \(Envoy.shared.proxy)")

            initWebView()

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
           let urlc = URLComponents(string: text),
           let url = urlc.url
        {
            addressTf.text = url.absoluteString

//            let conf = URLSessionConfiguration.default
//            conf.protocolClasses = [EnvoyUrlProtocol.self]
//
//            let session = URLSession(configuration: conf)
//
//            Task {
//                do {
//                    let (data, response) = try await session.data(for: URLRequest(url: url))
//
//                    log.debug("response=\(response), data=\(String(data: data, encoding: .utf8) ?? "(nil)")")
//                }
//                catch {
//                    log.error("\(error)")
//                }
//            }

            webView.stopLoading()
            webView.load(URLRequest(url: url))
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
        if let text = navigationAction.request.url?.absoluteString,
           !text.isEmpty
        {
            addressTf.text = text
        }

        decisionHandler(.allow)
    }

    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: any Error) {
        log.error("\(error)")
    }


    // MARK: Private Methods

    private func initWebView() {
        webView = EnvoyWebView(frame: .zero)
        webView.navigationDelegate = self
        webView.translatesAutoresizingMaskIntoConstraints = false

        view.addSubview(webView)

        webView.topAnchor.constraint(equalTo: addressTf.bottomAnchor, constant: 8).isActive = true
        webView.leadingAnchor.constraint(equalTo: view.leadingAnchor).isActive = true
        webView.trailingAnchor.constraint(equalTo: view.trailingAnchor).isActive = true
        webView.bottomAnchor.constraint(equalTo: view.bottomAnchor).isActive = true
    }
}
