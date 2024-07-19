//
//  EnvoySocksForwarder.swift
//  Envoy
//
//  Created by Benjamin Erhart on 10.06.24.
//

import Foundation
import IEnvoyProxy
import Network

class EnvoySocksForwarder {

    class Client: Equatable, CustomStringConvertible, Socks5Client.Delegate {

        // MARK: Equatable

        static func == (lhs: EnvoySocksForwarder.Client, rhs: EnvoySocksForwarder.Client) -> Bool {
            lhs.id == rhs.id
        }


        // MARK: CustomStringConvertible

        var description: String {
            "[\(String(describing: type(of: self))) id=\(id), proxy=\(proxy), local=\(local), remote=\(remote)]"
        }


        // MARK: Client

        typealias Completion = (_ error: Error?) -> Void


        let id = UUID()

        private let proxy: Envoy.Proxy
        private let local: NWConnection
        private var completion: Completion?
        private let queue = DispatchQueue(label: "EnvoySocksForwarder.Client", qos: .userInitiated,
                                          attributes: .concurrent, autoreleaseFrequency: .workItem)

        private let remote: Socks5Client

        init?(_ proxy: Envoy.Proxy, _ connection: NWConnection, _ completion: @escaping Completion) {
            let tunnel: Envoy.Proxy

            switch proxy {
            case .meek(_, _, let t),
                    .obfs4(_, _, let t),
                    .webTunnel(_, _, let t),
                    .snowflake(_, _, _, _, _, _, let t):
                tunnel = t

            default:
                return nil
            }

            let destination: NWEndpoint

            if case .socks5(let host, let port) = tunnel, port >= UInt16.min && port <= UInt16.max {
                destination = .hostPort(host: .init(host), port: .init(integerLiteral: UInt16(port)))
            }
            else {
                return nil
            }

            self.proxy = proxy
            local = connection
            self.completion = completion

            if let config = proxy.getProxyDict(forceTransport: true),
               let remote = Socks5Client(config, destination: destination)
            {
                self.remote = remote
                remote.delegate = self
            }
            else {
                return nil
            }

            connection.stateUpdateHandler = update(state:)

            connection.start(queue: queue)
        }

        // MARK: Socks5Client.Delegate

        func update(phase: Socks5Client.Phase) {
            switch phase {
            case .granted:
                Task {
                    do {
                        try await read()
                    }
                    catch {
                        complete(error)
                    }
                }

            case .error(let error):
                complete(error)

            default:
                break
            }
        }


        // MARK: Private Methods

        private func update(state: NWConnection.State) {
            switch state {
            case .failed(let nWError):
                complete(nWError)

            case .cancelled:
                complete(nil)

            default:
                break
            }
        }

        private func read() async throws {
            var completed = false

            let (request, isComplete1) = try await local.receive()

            guard let request = request, !request.isEmpty else {
                return complete(nil)
            }

            let (response, isComplete2) = try await remote.talk(request)
            completed = isComplete1 || isComplete2

            try await local.send(response)

            if completed {
                complete(nil)
            }
            else {
                try await read()
            }
        }

        private func complete(_ error: Error?) {
            guard let completion = completion else {
                // Was already called, hence everything already cancelled.
                // This most probably is a call because a pending read failed,
                // due to the cancel.
                return
            }

            self.completion = nil

            remote.cancel()

            switch local.state {
            case .failed, .cancelled:
                break

            default:
                local.stateUpdateHandler = nil
                local.cancel()
            }

            completion(error)
        }

        deinit {
            remote.cancel()

            if local.state != .cancelled {
                local.stateUpdateHandler = nil
                local.cancel()
            }
        }
    }


    static let port: UInt16 = 13370

    private let proxy: Envoy.Proxy

    private var listener: NWListener?

    init(_ proxy: Envoy.Proxy) {
        self.proxy = proxy
    }


    @discardableResult
    func start() throws -> Self {
        stop()

        listener = try NWListener(using: .tcp, on: .init(integerLiteral: Self.port))
        listener?.newConnectionHandler = newConnection(connection:)
        listener?.start(queue: .global(qos: .background))

        return self
    }

    @discardableResult
    func stop() -> Self {
        if listener?.state ?? .cancelled != .cancelled {
            listener?.newConnectionHandler = nil
            listener?.cancel()
            listener = nil
        }

        return self
    }


    // MARK: Private Methods

    private func newConnection(connection: NWConnection) {
        var client: Client?

        client = Client(proxy, connection) { error in
            let result: String

            if let error = error {
                result = "with error: \(error)"
            }
            else {
                result = "without error."
            }

            print("[\(String(describing: type(of: self)))] Client \(client!.id) completed \(result)")
        }

        if let client = client {
            print("[\(String(describing: type(of: self)))] Added client \(client.id)")
        }
    }

    deinit {
        stop()
    }
}
