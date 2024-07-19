//
//  EnvoyTests.swift
//  Envoy
//
//  Created by Benjamin Erhart on 12.07.24.
//

import XCTest
@testable import Envoy

class EnvoyTests: XCTestCase {

    func testParsing() {
        assertEnvoy("https://proxy.example.com/proxy/")

        assertEnvoy("https://proxy.example.com/proxy/")

        assertEnvoy("envoy://?url=https://proxy.example.com/proxy/&salt=abcdefghijklmnop&header_foobar=abcdefg&address=127.0.0.1",
                    proxyUrl: "https://127.0.0.1/proxy/",
                    headers: ["foobar": "abcdefg"],
                    salt: "abcdefghijklmnop")

        assertV2Ray(
            "v2ws://127.0.0.1:12345?id=00000000-0000-0000-0000-00000000000&path=/websocket/", 
            type: .ws,
            host: "127.0.0.1",
            port: 12345,
            id: "00000000-0000-0000-0000-00000000000",
            path: "/websocket/")

        assertV2Ray(
            "v2wechat://127.0.0.1:12346?id=00000000-0000-0000-0000-00000000000",
            type: .weChat,
            host: "127.0.0.1",
            port: 12346,
            id: "00000000-0000-0000-0000-00000000000")

        assertV2Ray(
            "v2srtp://127.0.0.1:12347?id=00000000-0000-0000-0000-00000000000",
            type: .srtp,
            host: "127.0.0.1",
            port: 12347,
            id: "00000000-0000-0000-0000-00000000000")

        assertHysteria2("hy2://abcdefghijklmnopqrstuvwxyzabcdef@example.com:12345/")

        assertHysteria2("hysteria2://letmein@example.com/?insecure=1&obfs=salamander&obfs-password=gawrgura&pinSHA256=deadbeef&sni=real.example.com")

        assertMeek("meek://?url=https://cdn.example.com/&front=.wellknown.org&tunnel=socks5://127.0.0.1:12345",
                   proxyUrl: "https://cdn.example.com/",
                   front: ".wellknown.org",
                   tunnel: .socks5(host: "127.0.0.1", port: 12345))

        assertMeek("meek://?url=https://cdn.example.com/&front=.wellknown.org&tunnel=https://proxy.example.com/proxy/",
                   proxyUrl: "https://cdn.example.com/",
                   front: ".wellknown.org",
                   tunnel: .envoy(url: URL(string: "https://proxy.example.com/proxy/")!))

        assertObfs4("obfs4://?cert=abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqr&tunnel=socks5://127.0.0.1:12345",
                    cert: "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqr",
                    iatMode: 0,
                    tunnel: .socks5(host: "127.0.0.1", port: 12345))

        assertObfs4("obfs4://?cert=abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqr&tunnel=https://proxy.example.com/proxy/",
                    cert: "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqr",
                    iatMode: 0,
                    tunnel: .envoy(url: URL(string: "https://proxy.example.com/proxy/")!))

        assertWebTunnel("webtunnel://?url=https://example.com/abcdefghijklm&ver=0.0.1&tunnel=socks5://127.0.0.1:12345",
                        proxyUrl: "https://example.com/abcdefghijklm",
                        ver: "0.0.1",
                        tunnel: .socks5(host: "127.0.0.1", port: 12345))

        assertWebTunnel("webtunnel://?url=https://example.com/abcdefghijklm&ver=0.0.1&tunnel=https://proxy.example.com/proxy/",
                        proxyUrl: "https://example.com/abcdefghijklm", 
                        ver: "0.0.1",
                        tunnel: .envoy(url: URL(string: "https://proxy.example.com/proxy/")!))

        assertSnowflake("snowflake://?broker=https://broker.example.com/&front=.wellknown.org&tunnel=socks5://127.0.0.1:12345",
                        broker: "https://broker.example.com/",
                        fronts: ".wellknown.org",
                        tunnel: .socks5(host: "127.0.0.1", port: 12345))

        assertSnowflake("snowflake://?broker=https://broker.example.com/&front=.wellknown.org&tunnel=https://proxy.example.com/proxy/",
                        broker: "https://broker.example.com/",
                        fronts: ".wellknown.org",
                        tunnel: .envoy(url: URL(string: "https://proxy.example.com/proxy/")!))
    }

    func testRequestModification() {
        var proxy = Envoy.Proxy.envoy(url: URL(string: "https://proxy.example.com/proxy/")!, headers: ["X-foobar": "abcdefg"])

        let request = URLRequest(url: URL(string: "https://example.com/")!)

        var modified = proxy.maybeModify(request)

        XCTAssertEqual(modified.url?.scheme, "https")
        XCTAssertEqual(modified.url?.host, "proxy.example.com")
        XCTAssertEqual(modified.url?.path, "/proxy")
        XCTAssertTrue(modified.url?.queryItems.contains(where: { $0.name == "_digest" }) == true)
        XCTAssertEqual(modified.allHTTPHeaderFields, ["Host-Orig": "example.com", "Url-Orig": "https://example.com/", "X-foobar": "abcdefg"])

        var reverted = proxy.revertModification(modified)

        XCTAssertEqual(request.url, reverted.url)

        proxy = Envoy.Proxy.webTunnel(
            url: URL(string: "https://example.com/abcdefghijklm")!,
            ver: "0.0.1",
            tunnel: .envoy(url: URL(string: "https://proxy.example.com/proxy/")!, headers: ["X-foobar": "abcdefg"]))

        modified = proxy.maybeModify(request)

        XCTAssertEqual(modified.url?.scheme, "https")
        XCTAssertEqual(modified.url?.host, "proxy.example.com")
        XCTAssertEqual(modified.url?.path, "/proxy")
        XCTAssertTrue(modified.url?.queryItems.contains(where: { $0.name == "_digest" }) == true)
        XCTAssertEqual(modified.allHTTPHeaderFields, ["Host-Orig": "example.com", "Url-Orig": "https://example.com/", "X-foobar": "abcdefg"])

        reverted = proxy.revertModification(modified)

        XCTAssertEqual(request.url, reverted.url)
    }

    func testProxyDict() {
        var proxy = Envoy.Proxy.obfs4(cert: "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqr",
                                      iatMode: 2, tunnel: .direct)

        var dict = proxy.getProxyDict()

        XCTAssertNotNil(dict)
        XCTAssertEqual(dict?[kCFProxyTypeKey] as! CFString, kCFProxyTypeSOCKS)
        XCTAssertEqual(dict?[kCFStreamPropertySOCKSVersion] as! CFString, kCFStreamSocketSOCKSVersion5)
        XCTAssertEqual(dict?[kCFStreamPropertySOCKSProxyHost] as! String, "127.0.0.1")
        XCTAssertEqual(dict?[kCFStreamPropertySOCKSProxyPort] as! Int, 47300)

        // Dictionaries don't have order, so the order of the arguments changes randomly on reruns.
        let arguments = (dict?[kCFStreamPropertySOCKSUser] as! String) + (dict?[kCFStreamPropertySOCKSPassword] as! String)

        // Hence we cannot test for a complete string, but just if the arguments are contained
        XCTAssertTrue(arguments.contains("iat-mode=2"))
        XCTAssertTrue(arguments.contains(";"))
        XCTAssertTrue(arguments.contains("cert=abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqr"))


        proxy = .meek(url: URL(string: "https://cdn.example.com/")!, front: ".wellknown.org", tunnel: .socks5(host: "127.0.0.1", port: 12345))

        dict = proxy.getProxyDict()

        XCTAssertNotNil(dict)
        XCTAssertEqual(dict?[kCFProxyTypeKey] as! CFString, kCFProxyTypeSOCKS)
        XCTAssertEqual(dict?[kCFStreamPropertySOCKSVersion] as! CFString, kCFStreamSocketSOCKSVersion5)
        XCTAssertEqual(dict?[kCFStreamPropertySOCKSProxyHost] as! String, "127.0.0.1")
        XCTAssertEqual(dict?[kCFStreamPropertySOCKSProxyPort] as! Int, Int(EnvoySocksForwarder.port))
        XCTAssertNil(dict?[kCFStreamPropertySOCKSUser])
        XCTAssertNil(dict?[kCFStreamPropertySOCKSPassword])
    }

    @available(iOS 17.0, macOS 14.0, *)
    func testProxyConf() {
        var proxy = Envoy.Proxy.obfs4(cert: "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqr",
                                      iatMode: 2, tunnel: .direct)

        var conf = proxy.getProxyConfig()

        XCTAssertNotNil(conf)
        XCTAssertEqual(conf?.debugDescription, "socksv5 Proxy: 127.0.0.1:47300")


        proxy = .meek(url: URL(string: "https://cdn.example.com/")!, front: ".wellknown.org", tunnel: .socks5(host: "127.0.0.1", port: 12345))

        conf = proxy.getProxyConfig()

        XCTAssertNotNil(conf)
        XCTAssertEqual(conf?.debugDescription, "socksv5 Proxy: 127.0.0.1:\(EnvoySocksForwarder.port)")

    }

    private func assertProxy(_ url: String) -> Envoy.Proxy {
        let proxy = Envoy.shared.parse(URL(string: url)!)
        XCTAssertNotNil(proxy)

        return proxy!
    }

    private func assertEnvoy(_ url: String, proxyUrl: String? = nil, headers: [String: String] = [:], salt: String? = nil) {
        let proxy = assertProxy(url)

        if case .envoy(let url2, let headers2, let salt2) = proxy {
            XCTAssertEqual(url2, URL(string: proxyUrl ?? url))
            XCTAssertEqual(headers2, headers)
            XCTAssertEqual(salt2, salt)
        }
        else {
            XCTFail("This URL should have parsed to an Envoy HTTP proxy, but didn't!")
        }
    }

    private func assertV2Ray(_ url: String, type: Envoy.Proxy.V2RayType, host: String, port: Int, id: String, path: String? = nil) {
        let proxy = assertProxy(url)

        if case .v2Ray(let type2, let host2, let port2, let id2, let path2) = proxy {
            XCTAssertEqual(type2, type)
            XCTAssertEqual(host2, host)
            XCTAssertEqual(port2, port2)
            XCTAssertEqual(id2, id)
            XCTAssertEqual(path2, path)
        }
        else {
            XCTFail("This URL should have parsed to a V2Ray proxy, but didn't!")
        }
    }

    private func assertHysteria2(_ url: String) {
        let proxy = assertProxy(url)

        if case .hysteria2(let url2) = proxy {
            XCTAssertEqual(url2, URL(string: url))
        }
        else {
            XCTFail("This URL should have parsed to a Hysteria2 proxy, but didn't!")
        }
    }

    private func assertMeek(_ url: String, proxyUrl: String, front: String, tunnel: Envoy.Proxy) {
        let proxy = assertProxy(url)

        if case .meek(let url2, let front2, let tunnel2) = proxy {
            XCTAssertEqual(url2, URL(string: proxyUrl))
            XCTAssertEqual(front2, front)
            XCTAssertEqual(tunnel2, tunnel)
        }
        else {
            XCTFail("This URL should have parsed to a Meek PT proxy, but didn't!")
        }
    }

    private func assertObfs4(_ url: String, cert: String, iatMode: Int, tunnel: Envoy.Proxy) {
        let proxy = assertProxy(url)

        if case .obfs4(let cert2, let iatMode2, let tunnel2) = proxy {
            XCTAssertEqual(cert2, cert)
            XCTAssertEqual(iatMode2, iatMode)
            XCTAssertEqual(tunnel2, tunnel)
        }
        else {
            XCTFail("This URL should have parsed to an Obfs4 PT proxy, but didn't!")
        }
    }

    private func assertWebTunnel(_ url: String, proxyUrl: String, ver: String, tunnel: Envoy.Proxy) {
        let proxy = assertProxy(url)

        if case .webTunnel(let url2, let ver2, let tunnel2) = proxy {
            XCTAssertEqual(url2, URL(string: proxyUrl))
            XCTAssertEqual(ver2, ver)
            XCTAssertEqual(tunnel2, tunnel)
        }
        else {
            XCTFail("This URL should have parsed to WebTunnel PT proxy, but didn't!")
        }
    }

    private func assertSnowflake(_ url: String, ice: String? = nil, broker: String, 
                                 fronts: String, ampCache: String? = nil, sqsQueue: String? = nil,
                                 sqsCreds: String? = nil, tunnel: Envoy.Proxy)
    {
        let proxy = assertProxy(url)

        if case .snowflake(let ice2, let broker2, let fronts2, let ampCache2, let sqsQueue2, let sqsCreds2, let tunnel2) = proxy {
            XCTAssertEqual(ice2, ice ?? Envoy.defaultIceServers)
            XCTAssertEqual(broker2, URL(string: broker))
            XCTAssertEqual(fronts2, fronts)
            XCTAssertEqual(ampCache2, ampCache)
            XCTAssertEqual(sqsQueue2, URL(string: sqsQueue ?? ""))
            XCTAssertEqual(sqsCreds2, sqsCreds)
            XCTAssertEqual(tunnel2, tunnel)
        }
        else {
            XCTFail("This URL should have parsed to an Obfs4 PT proxy, but didn't!")
        }
    }
}
