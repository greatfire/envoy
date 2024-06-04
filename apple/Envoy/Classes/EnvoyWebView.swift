//
//  EnvoyWebView.swift
//  Envoy
//
//  Created by Benjamin Erhart on 04.06.24.
//

import WebKit

/**
 This class helps you work around the limitations of `WKWebView` by

 - installing the selected proxy as a SOCKS5 proxy, *if* on iOS 17 or above *and if* the selected proxy supports that.
 - installing a custom scheme handler for the schemes "envoy-http" and "envoy-https",
 - automatically sets `isInspectable = true` on newer iOS versions when in `DEBUG` mode,
 - replaces `http` and `https` request URL schemes with `envoy-http` and `envoy-https`, *if*  the Envoy custom HTTP proxy is used *or  if* the iOS version is below 17, to redirect processing to the `EnvoySchemeHandler`.

 **NOTES**:

 Run `Envoy.shared.start(urls:testUrl:testDirect:)` or `Envoy.shared.start(proxies:testUrl:testDirect:)` first, before initializing this web view!

 `WKWebView` has severe limitations which make it difficult to run in certain circumstances:

 - Before iOS 17, it didn't support SOCKS and HTTP proxies.
 - Intercepting `http` and `https` schemes is not allowed.

  These limitations have the following implications:
 - Only requests to URLs which the user issues explicitly can be rewritten to use a custom scheme.
 - Also requests, for which `WKWebView` asks about a `WKNavigationActionPolicy` can be rewritten.
 - Relative URLs will implicitly use the custom scheme.
 - Everything else (in other words all fully qualified links in HTML, CSS, JS, SVG and probably more file types) cannot be handled with the `EnvoySchemeHandler`.
 - `WKWebView` is dangerous to use *before* iOS 17, because it lacks real proxy support.

 In other words, your users are **safe** under the following conditions:
 - They run iOS 17.
 - They are using a proxy which can talk SOCKS5 directly: Anything which **does not use** the Envoy proxy in its chain!

 Unfortunately, the Envoy proxy works by modifying the HTTP request slightly. This cannot be achieved
 with a SOCKS5 or HTTP proxy interface, since the request is already encrypted then, and the proxy
 only gets to see a destination server address, but no further details about the request.

 For opportunistic tunneling, these limitations might be ok and rewriting some of the requests with the `EnvoySchemeHandler`
 might just be fine. (E.g. Wikipedia actually works pretty well this way, since they mostly use relative URLs, which will be relative to the custom scheme.)

 But if you need to make sure, your users **do not accidentally make requests which bypass the proxy**,
 the following adivce applies:

 - **DO NOT USE** the Envoy proxy. Also not as the thing to wrap `Obfs4`, `Meek`, `WebTunnel` or `Snowflake` transports around.
 - **DO NOT** support iOS before version 17.
 */
open class EnvoyWebView: WKWebView, WKNavigationDelegate {

    private var _navigationDelegate: WKNavigationDelegate?
    open override var navigationDelegate: WKNavigationDelegate? {
        set {
            _navigationDelegate = newValue
        }
        get {
            _navigationDelegate
        }
    }

    public override init(frame: CGRect, configuration: WKWebViewConfiguration = .init()) {
        if #available(iOS 17.0, *) {
            if let proxy = Envoy.shared.getProxyConfig() {
                configuration.websiteDataStore.proxyConfigurations.append(proxy)
            }
        }

        super.init(frame: frame, configuration: EnvoySchemeHandler.register(configuration))

#if DEBUG
        if #available(iOS 16.4, *) {
            isInspectable = true
        }
#endif

        super.navigationDelegate = self
    }

    required public init?(coder: NSCoder) {
        super.init(coder: coder)
    }


    // MARK: Overrides

    @discardableResult
    open override func load(_ request: URLRequest) -> WKNavigation? {
        if Self.needsModification(request) {
            return super.load(EnvoySchemeHandler.modify(request))
        }

        return super.load(request)
    }


    // MARK: WKNavigationDelegate

    public func webView(_ webView: WKWebView, 
                 decidePolicyFor navigationAction: WKNavigationAction,
                 preferences: WKWebpagePreferences,
                 decisionHandler: @escaping (WKNavigationActionPolicy, WKWebpagePreferences) -> Void)
    {
        let handler = { (policy: WKNavigationActionPolicy, preferences: WKWebpagePreferences) in
            if Self.needsModification(navigationAction.request) {
                if policy == .cancel {
                    return decisionHandler(policy, preferences)
                }

                let modified = EnvoySchemeHandler.modify(navigationAction.request)

                // This request doesn't need to get modified or already is. Allow.
                if modified == navigationAction.request {
                    return decisionHandler(policy, preferences)
                }

                // This request needs to be modified. Cancel and re-issue.
                decisionHandler(.cancel, preferences)

                DispatchQueue.main.async {
                    webView.load(modified)
                }
            }
            else {
                decisionHandler(policy, preferences)
            }
        }

        let action = EnvoyNavigationAction(from: navigationAction, with: EnvoySchemeHandler.revertModification(navigationAction.request))

        if _navigationDelegate?.webView?(webView, decidePolicyFor: action, preferences: preferences, decisionHandler: handler) != nil {
            return
        }

        guard _navigationDelegate?.webView?(webView, decidePolicyFor: action, decisionHandler: { policy in
            handler(policy, preferences)
        }) != nil
        else {
            return handler(.allow, preferences)
        }
    }

    public func webView(_ webView: WKWebView,
                        decidePolicyFor navigationResponse: WKNavigationResponse,
                        decisionHandler: @escaping (WKNavigationResponsePolicy) -> Void)
    {
        guard _navigationDelegate?.webView?(webView, decidePolicyFor: navigationResponse, decisionHandler: decisionHandler) != nil
        else {
            return decisionHandler(.allow)
        }
    }

    public func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
        _navigationDelegate?.webView?(webView, didStartProvisionalNavigation: navigation)
    }

    public func webView(_ webView: WKWebView, didReceiveServerRedirectForProvisionalNavigation navigation: WKNavigation!) {
        _navigationDelegate?.webView?(webView, didReceiveServerRedirectForProvisionalNavigation: navigation)
    }

    public func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: any Error) {
        _navigationDelegate?.webView?(webView, didFailProvisionalNavigation: navigation, withError: error)
    }

    public func webView(_ webView: WKWebView, didCommit navigation: WKNavigation!) {
        _navigationDelegate?.webView?(webView, didCommit: navigation)
    }

    public func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        _navigationDelegate?.webView?(webView, didFinish: navigation)
    }

    public func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: any Error) {
        _navigationDelegate?.webView?(webView, didFail: navigation, withError: error)
    }

    public func webView(_ webView: WKWebView, 
                        didReceive challenge: URLAuthenticationChallenge,
                        completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void)
    {
        guard _navigationDelegate?.webView?(webView, didReceive: challenge, completionHandler: completionHandler) != nil
        else {
            return completionHandler(.performDefaultHandling, nil)
        }
    }

    public func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
        _navigationDelegate?.webViewWebContentProcessDidTerminate?(webView)
    }

    @available(iOS 14.0, *)
    public func webView(_ webView: WKWebView, 
                        authenticationChallenge challenge: URLAuthenticationChallenge,
                        shouldAllowDeprecatedTLS decisionHandler: @escaping (Bool) -> Void)
    {
        guard _navigationDelegate?.webView?(webView, authenticationChallenge: challenge, shouldAllowDeprecatedTLS: decisionHandler) != nil
        else {
            return decisionHandler(false)
        }
    }

    @available(iOS 14.5, *)
    public func webView(_ webView: WKWebView, navigationAction: WKNavigationAction, didBecome download: WKDownload) {
        _navigationDelegate?.webView?(webView, navigationAction: navigationAction, didBecome: download)
    }

    @available(iOS 14.5, *)
    public func webView(_ webView: WKWebView, navigationResponse: WKNavigationResponse, didBecome download: WKDownload) {
        _navigationDelegate?.webView?(webView, navigationResponse: navigationResponse, didBecome: download)
    }


    // MARK: Private Methods

    private class func needsModification(_ request: URLRequest) -> Bool {
        if #available(iOS 17.0, *) {
            switch Envoy.shared.proxy {
            case .direct, .v2Ray, .hysteria2:
                return false

            case .envoy:
                break

            case .meek(_, _, let tunnel):
                switch tunnel {
                case .envoy:
                    break

                default:
                    return false
                }

            case .obfs4(_, _, let tunnel):
                switch tunnel {
                case .envoy:
                    break

                default:
                    return false
                }

            case .snowflake(_, _, _, _, _, _, let tunnel):
                switch tunnel {
                case .envoy:
                    break

                default:
                    return false
                }
            }
        }

        return true
    }
}

class EnvoyNavigationAction: WKNavigationAction {

    override var sourceFrame: WKFrameInfo {
        original.sourceFrame
    }

    override var targetFrame: WKFrameInfo? {
        original.targetFrame
    }

    override var navigationType: WKNavigationType {
        original.navigationType
    }

    override var request: URLRequest {
        modified
    }

    @available(iOS 14.5, *)
    override var shouldPerformDownload: Bool {
        original.shouldPerformDownload
    }

    private let original: WKNavigationAction

    private let modified: URLRequest

    init(from action: WKNavigationAction, with modified: URLRequest) {
        original = action
        self.modified = modified

        super.init()
    }
}
