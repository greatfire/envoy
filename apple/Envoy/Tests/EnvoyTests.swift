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
        var urlo = URL(string: "https://proxy.example.com/proxy/")!

        var proxy = Envoy.shared.parse(urlo)
        XCTAssertNotNil(proxy)

        if case .envoy(let url, let headers, let salt) = proxy {
            XCTAssertEqual(url, urlo)
            XCTAssertTrue(headers.isEmpty)
            XCTAssertNil(salt)
        }
        else {
            XCTFail("This URL should have parsed to an Envoy HTTP proxy, but didn't!")
        }

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
                   tunnel: .envoy(url: URL(string: "https://proxy.example.com/proxy/")!, headers: [:], salt: nil))

        assertObfs4("obfs4://?cert=abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqr&tunnel=socks5://127.0.0.1:12345",
                    cert: "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqr",
                    iatMode: 0,
                    tunnel: .socks5(host: "127.0.0.1", port: 12345))

        assertObfs4("obfs4://?cert=abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqr&tunnel=https://proxy.example.com/proxy/",
                    cert: "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqr",
                    iatMode: 0,
                    tunnel: .envoy(url: URL(string: "https://proxy.example.com/proxy/")!, headers: [:], salt: nil))

        assertSnowflake("snowflake://?broker=https://broker.example.com/&front=.wellknown.org&tunnel=socks5://127.0.0.1:12345",
                        broker: "https://broker.example.com/",
                        fronts: ".wellknown.org",
                        tunnel: .socks5(host: "127.0.0.1", port: 12345))

        assertSnowflake("snowflake://?broker=https://broker.example.com/&front=.wellknown.org&tunnel=https://proxy.example.com/proxy/",
                        broker: "https://broker.example.com/",
                        fronts: ".wellknown.org",
                        tunnel: .envoy(url: URL(string: "https://proxy.example.com/proxy/")!, headers: [:], salt: nil))
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