//
//  ViewController.swift
//  Envoy
//
//  Created by Benjamin Erhart on 10.07.24.
//  Copyright © 2024 GreatFire. Licensed under Apache-2.0.
//

import Cocoa
@preconcurrency import WebKit
import OSLog
import GreatfireEnvoy

class ViewController: NSViewController, WKNavigationDelegate {

    @IBOutlet weak var addressTf: NSTextField!

    @IBOutlet weak var busyView: NSView!
    @IBOutlet weak var progress: NSProgressIndicator!

    private var webView: EnvoyWebView?

    private let log = Logger(for: ViewController.self)

    override func viewDidLoad() {
        super.viewDidLoad()

        addressTf.stringValue = "https://www.wikipedia.org"

        busyView.layer?.zPosition = 1000
        progress.startAnimation(nil)

        Task {
            Envoy.ptLogging = true
            log.debug("ptStateDir=\(Envoy.ptStateDir?.path ?? "(nil)")")

            let proxies = Proxy.fetch()
            log.debug("proxies=\(proxies)")

            await Envoy.shared.start(urls: proxies.map({ $0.url }), testDirect: proxies.isEmpty)

            log.debug("selected proxy: \(Envoy.shared.proxy)")

            initWebView()

            busyView.isHidden = true
            progress.stopAnimation(nil)

            startNavigation(nil)
        }
    }

    override var representedObject: Any? {
        didSet {
        // Update the view, if already loaded.
        }
    }


    // MARK: Actions

    @IBAction func startNavigation(_ sender: NSTextField?) {
        let text = addressTf.stringValue.trimmingCharacters(in: .whitespacesAndNewlines)

        if let urlc = URLComponents(string: text),
           let url = urlc.url
        {
            addressTf.stringValue = url.absoluteString

            webView?.stopLoading()
            webView?.load(URLRequest(url: url))
        }
    }


    // MARK: WKNavigationDelegate

    func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction,
                 decisionHandler: @escaping (WKNavigationActionPolicy) -> Void)
    {
        if let text = navigationAction.request.url?.absoluteString,
           !text.isEmpty
        {
            addressTf.stringValue = text
        }

        decisionHandler(.allow)
    }

    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: any Error) {
        log.error("\(error)")
    }


    // MARK: Private Methods

    private func initWebView() {
        let webView = EnvoyWebView(frame: .zero)
        webView.navigationDelegate = self
        webView.translatesAutoresizingMaskIntoConstraints = false

        view.addSubview(webView)

        webView.topAnchor.constraint(equalTo: addressTf.bottomAnchor, constant: 8).isActive = true
        webView.leadingAnchor.constraint(equalTo: view.leadingAnchor).isActive = true
        webView.trailingAnchor.constraint(equalTo: view.trailingAnchor).isActive = true
        webView.bottomAnchor.constraint(equalTo: view.bottomAnchor).isActive = true

        self.webView = webView
    }
}
