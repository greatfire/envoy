//
//  Socks5Client.swift
//  Envoy
//
//  Created by Benjamin Erhart on 19.06.24.
//

import Network

/**
 A basic SOCKS5 server implementation, which supports no authentication and username/password authentication.

 (Yes, and no GSSAPI, as the RFC mandates and nobody implements...)

 The ``Cmd.connnect`` (aka. TCP stream), is fully supported using the ``Socks5Client.talk`` method.

 Other commands might be possible, but you're on your own with these!
 */
open class Socks5Client: CustomStringConvertible {

    public protocol Delegate: AnyObject {

        func update(phase: Phase)
    }

    public enum Errors: LocalizedError {

        case illegalResponse
        case illegalSocksVersion
        case unsupportedAuthMethod
        case unaceptableAuthMethods
        case authenticationFailure(status: UInt8)
        case socksServerError(status: Reply)
        case invalidData
        case cancelled
        case illegalPhase

        public var errorDescription: String? {
            switch self {
            case .illegalResponse:
                return "Illegal response"

            case .illegalSocksVersion:
                return "Illegal SOCKS version"

            case .unsupportedAuthMethod:
                return "Server offered authentication methods not supported by this implementation"

            case .unaceptableAuthMethods:
                return "Requested authentication methods not acceptable by server"

            case .authenticationFailure(let status):
                return "Authentication failure: \(status)"

            case .socksServerError(let status):
                return "SOCKS server: \(status)"

            case .invalidData:
                return "Invalid data"

            case .cancelled:
                return "Cancelled"

            case .illegalPhase:
                return "Illegal Phase - try again when phase is `.granted`!"
            }
        }
    }

    public enum Ver: UInt8 {
        case v4 = 4
        case v5 = 5
    }

    public enum AuthMethod {
        case noAuthentication
        case gssApi
        case usernamePassword
        case challengeHandshakeAuthenticationProtocol
        case challengeResponseAuthenticationMethod
        case secureSocketsLayer
        case ndsAuthentication
        case multiAuthenticationFramework
        case jsonParameterBlock
        case unassigned(id: UInt8)
        case reservedForPrivateUse(id: UInt8)
        case noAcceptableMethods

        public var rawValue: UInt8 {
            switch self {
            case .noAuthentication:
                return 0

            case .gssApi:
                return 1

            case .usernamePassword:
                return 2

            case .challengeHandshakeAuthenticationProtocol:
                return 3

            case .challengeResponseAuthenticationMethod:
                return 5

            case .secureSocketsLayer:
                return 6

            case .ndsAuthentication:
                return 7

            case .multiAuthenticationFramework:
                return 8

            case .jsonParameterBlock:
                return 9

            case .unassigned(let id):
                return id

            case .reservedForPrivateUse(let id):
                return id

            case .noAcceptableMethods:
                return 0xff
            }
        }

        public static func from(raw: UInt8) -> Self {
            switch raw {
            case 0:
                return .noAuthentication

            case 1:
                return .gssApi

            case 2:
                return .usernamePassword

            case 3:
                return .challengeHandshakeAuthenticationProtocol

            case 4:
                return .unassigned(id: raw)

            case 5:
                return .challengeResponseAuthenticationMethod

            case 6:
                return .secureSocketsLayer

            case 7:
                return .ndsAuthentication

            case 8:
                return .multiAuthenticationFramework

            case 9:
                return .jsonParameterBlock

            case 0xff:
                return .noAcceptableMethods

            default:
                if raw >= 0x0a && raw <= 0x7f {
                    return .unassigned(id: raw)
                }
                else {
                    return .reservedForPrivateUse(id: raw)
                }
            }
        }
    }

    public enum AuthStatus {
        case success
        case failure(id: UInt8)

        public var rawValue: UInt8 {
            switch self {
            case .success:
                return 0

            case .failure(let id):
                return id
            }
        }

        public static func from(raw: UInt8) -> Self {
            switch raw {
            case 0:
                return .success

            default:
                return .failure(id: raw)
            }
        }
    }

    public enum Command: UInt8 {
        case connect = 1
        case bind = 2
        case udpAssociate = 3
    }

    public enum Reply: CustomStringConvertible {

        case requestGranted
        case generalFailure
        case connectionNotAllowedByRuleset
        case networkUnreachable
        case hostUnreachable
        case connectionRefusedByDestinationHost
        case ttlExpired
        case commandNotSupportedProtocolError
        case addressTypeNotSupported
        case unknown(id: UInt8)

        public var rawValue: UInt8 {
            switch self {
            case .requestGranted:
                return 0

            case .generalFailure:
                return 1

            case .connectionNotAllowedByRuleset:
                return 2

            case .networkUnreachable:
                return 3

            case .hostUnreachable:
                return 4

            case .connectionRefusedByDestinationHost:
                return 5

            case .ttlExpired:
                return 6

            case .commandNotSupportedProtocolError:
                return 7

            case .addressTypeNotSupported:
                return 8

            case .unknown(let id):
                return id
            }
        }

        public var description: String {
            switch self {
            case .requestGranted:
                return "request granted"

            case .generalFailure:
                return "general failure"

            case .connectionNotAllowedByRuleset:
                return "connection not allowed by ruleset"

            case .networkUnreachable:
                return "network unreachable"

            case .hostUnreachable:
                return "host unreachable"

            case .connectionRefusedByDestinationHost:
                return "connection refused by destination host"

            case .ttlExpired:
                return "TTL expired"

            case .commandNotSupportedProtocolError:
                return "command not supported / protocol error"

            case .addressTypeNotSupported:
                return "address type not supported"

            case .unknown(let id):
                return "unknown error code \(id)"
            }
        }

        public static func from(raw: UInt8) -> Self {
            switch raw {
            case 0:
                return .requestGranted

            case 1:
                return .generalFailure

            case 2:
                return .connectionNotAllowedByRuleset

            case 3:
                return .networkUnreachable

            case 4:
                return .hostUnreachable

            case 5:
                return .connectionRefusedByDestinationHost

            case 6:
                return .ttlExpired

            case 7:
                return .commandNotSupportedProtocolError

            case 8:
                return .addressTypeNotSupported

            default:
                return .unknown(id: raw)
            }
        }
    }

    public enum Req {
        case greeting
        case auth
        case conn

        public var minLength: Int {
            switch self {
            case .greeting:
                return 2

            case .auth:
                return 3

            case .conn:
                return 7
            }
        }

        public var maxLength: Int {
            switch self {
            case .greeting:
                return 257

            case .auth:
                return 513

            case .conn:
                return 262
            }
        }

        public func parse(_ data: Data) throws -> Message {
            switch self {
            case .greeting:
                if data.count < 2 {
                    throw Errors.invalidData
                }

                guard let ver = Ver(rawValue: data[0]) else {
                    throw Errors.illegalSocksVersion
                }

                let nmethods = Int(data[1])

                if data.count < 2 + nmethods {
                    throw Errors.invalidData
                }

                var methods = [AuthMethod]()

                for i in 0 ..< nmethods {
                    methods.append(AuthMethod.from(raw: data[2 + i]))
                }

                return .greeting(ver: ver, methods: methods)

            case .auth:
                if data.count < 3 {
                    throw Errors.invalidData
                }

                let idlen = Int(data[1])

                if data.count < 2 + idlen + 1 {
                    throw Errors.invalidData
                }

                let pwlen = Int(data[2 + idlen])

                if data.count < 2 + idlen + 1 + pwlen {
                    throw Errors.invalidData
                }

                return .auth(
                    ver: data[0],
                    username: data.subdata(in: 2 ..< (2 + idlen)),
                    password: data.subdata(in: (2 + idlen) ..< (2 + idlen + 1 + pwlen)))

            case .conn:
                if data.count < 10 {
                    throw Errors.invalidData
                }

                guard let ver = Ver(rawValue: data[0]) else {
                    throw Errors.illegalSocksVersion
                }

                guard let cmd = Command(rawValue: data[1]),
                      let host = NWEndpoint.Host.from(socks5: data.subdata(in: 3 ..< data.endIndex - 2)),
                      let port = NWEndpoint.Port(socks5: data.subdata(in: data.endIndex - 2 ..< data.endIndex))
                else {
                    throw Errors.invalidData
                }

                return .connRequest(ver: ver, cmd: cmd, host: host, port: port)
            }
        }
    }

    public enum Res {
        case choice
        case auth
        case conn

        public var minLength: Int {
            switch self {
            case .choice, .auth:
                return 2

            case .conn:
                return 7
            }
        }

        public var maxLength: Int {
            switch self {
            case .choice, .auth:
                return 2

            case .conn:
                return 262
            }
        }

        public func parse(_ data: Data) throws -> Message {
            if data.count < minLength || data.count > maxLength {
                throw Errors.invalidData
            }

            switch self {
            case .choice:
                guard let ver = Ver(rawValue: data[0]) else {
                    throw Errors.illegalSocksVersion
                }

                return .authChoice(ver: ver, method: .from(raw: data[1]))

            case .auth:
                return .authResponse(ver: data[0], status: .from(raw: data[1]))

            case .conn:
                guard let ver = Ver(rawValue: data[0]) else {
                    throw Errors.illegalSocksVersion
                }

                var host: NWEndpoint.Host?
                var port: NWEndpoint.Port?

                if data.count > 3 {
                    host = .from(socks5: data.subdata(in: 3 ..< data.endIndex - 2))

                    if host != nil {
                        port = .init(socks5: data.subdata(in: data.endIndex - 2 ..< data.endIndex))
                    }
                }

                return .connReply(ver: ver, reply: .from(raw: data[1]), bndAddr: host, bndPort: port)
            }
        }
    }

    public enum Message {

        case greeting(ver: Ver = .v5, methods: [AuthMethod])
        case authChoice(ver: Ver = .v5, method: AuthMethod)
        case auth(ver: UInt8 = 1, username: Data?, password: Data?)
        case authResponse(ver: UInt8 = 1, status: AuthStatus)
        case connRequest(ver: Ver = .v5, cmd: Command = .connect, host: NWEndpoint.Host, port: NWEndpoint.Port)
        case connReply(ver: Ver = .v5, reply: Reply, bndAddr: NWEndpoint.Host?, bndPort: NWEndpoint.Port?)

        public var raw: Data {
            switch self {
            case .greeting(let ver, let auth):
                var data = Data([ver.rawValue, UInt8(auth.count)])

                for a in auth {
                    data.append(a.rawValue)
                }

                return data

            case .authChoice(let ver, let cauth):
                return Data([ver.rawValue, cauth.rawValue])

            case .auth(let ver, let user, let password):
                var data = Data([ver,
                                 UInt8(user?.count ?? 0)])

                if let user = user, !user.isEmpty {
                    data.append(contentsOf: user[0 ..< min(user.endIndex, 255)])
                }

                data.append(UInt8(password?.count ?? 0))

                if let password = password, !password.isEmpty {
                    data.append(contentsOf: password[ 0 ..< min(password.endIndex, 255)])
                }

                return data

            case .authResponse(let ver, let status):
                return Data([ver, status.rawValue])

            case .connRequest(let ver, let cmd, let host, let port):
                var data = Data([ver.rawValue,
                                 cmd.rawValue,
                                 0 /* RSV */])

                data.append(contentsOf: host.socks5)
                data.append(contentsOf: port.socks5)

                return data

            case .connReply(let ver, let status, let bndAddr, let bndPort):
                var data = Data([ver.rawValue, status.rawValue, 0 /* RSV */])

                if let host = bndAddr?.socks5 {
                    data.append(contentsOf: host)

                    if let port = bndPort?.socks5 {
                        data.append(contentsOf: port)
                    }
                }

                return data
            }
        }
    }

    public enum Phase {
        case greeting
        case auth
        case conn
        case granted(bndAddr: NWEndpoint.Host?, bndPort: NWEndpoint.Port?)
        case error(error: Error)

        public func req() throws -> Req? {
            switch self {
            case .greeting:
                return .greeting

            case .auth:
                return .auth

            case .conn:
                return .conn

            case .granted:
                return nil

            case .error(let error):
                throw error
            }
        }

        public func res() throws -> Res {
            switch self {
            case .greeting:
                return .choice

            case .auth:
                return .auth

            case .conn:
                return .conn

            case .granted:
                throw Errors.illegalPhase

            case .error(let error):
                throw error
            }
        }
    }


    // MARK: CustomStringConvertible

    public var description: String {
        "[\(String(describing: type(of: self))) connection=\(connection), destination=\(destHost):\(destPort)]"
    }


    // MARK: Socks5Client

    public static let second: UInt64 = 1_000_000_000

    public let connection: NWConnection
    public let cmd: Command
    public let destHost: NWEndpoint.Host
    public let destPort: NWEndpoint.Port
    public let user: Data?
    public let password: Data?

    public weak var delegate: Delegate?

    public private(set) var phase = Phase.greeting {
        didSet {
            delegate?.update(phase: phase)
        }
    }

    private let queue = DispatchQueue(label: "EnvoySocksForwarder.SocksClient", qos: .userInitiated,
                                      attributes: .concurrent, autoreleaseFrequency: .workItem)


    /**
     Create a `Socks5Client`.

     This will return `nil`, if your parameters are not as expected.

     - parameter config: A SOCKS5 server configuration dictionary.
     - parameter destination: A ``NWEndpoint.hostPort`` which defines the destination we want to talk to through the SOCKS5 server.
     - parameter cmd: The ``Command`` you wish to initiate. ``Command.connect`` is the default and fully supported. With all others, you're on yourself.
     */
    public init?(_ config: [AnyHashable: Any], destination: NWEndpoint, cmd: Command = .connect) {
        guard let type = config[kCFProxyTypeKey] as? String,
              type == String(kCFProxyTypeSOCKS),
              let version = config[kCFStreamPropertySOCKSVersion] as? String,
              version == String(kCFStreamSocketSOCKSVersion5),
              let host = config[kCFStreamPropertySOCKSProxyHost] as? String,
              let port = config[kCFStreamPropertySOCKSProxyPort] as? Int,
              port >= UInt16.min && port <= UInt16.max
        else {
            return nil
        }

        if case .hostPort(let host, let port) = destination {
            destHost = host
            destPort = port
        }
        else {
            return nil
        }

        connection = NWConnection(host: .init(host), port: .init(integerLiteral: UInt16(port)), using: .tcp)

        self.cmd = cmd

        user = (config[kCFStreamPropertySOCKSUser] as? String)?.data(using: .utf8, allowLossyConversion: true)
        password = (config[kCFStreamPropertySOCKSPassword] as? String)?.data(using: .utf8, allowLossyConversion: true)

        connection.stateUpdateHandler = update(state:)

        connection.start(queue: queue)
    }

    /**
     Sends the given data and receives the expected amount of data, which it will return.

     This should only be called when the phase of the SOCKS5 dialog is in state ``Phase.granted``! Otherwise, this will throw an error.

     You best call this, when you receive a phase update in  ``Delegate.update(phase:)``.

     - parameter request: Data to send.
     - parameter min: Minimum amount of bytes expected to receive..
     - parameter max: Maximum amount of bytes expected to receive.
     - returns: the received data.
     - throws: Any error that happened during the SOCKS5 negotiation, should the negotiaton gone awry, ``Errors.illegalPhase``, if not in ``Phase.granted`` or any error that happens during send and receive.
     */
    open func talk(_ request: Data, expect min: Int = 0, to max: Int = .max) async throws -> (response: Data?, isComplete: Bool) {
        switch phase {
        case .granted:
            break

        case .error(let error):
            throw error

        default:
            throw Errors.illegalPhase
        }

        try await connection.send(request)

        // If we don't do this, the connection will be instable and often break.
        try await Task.sleep(nanoseconds: Self.second/10)

        let response = try await connection.receive(min: min, max: max)

        return (response: response.content, isComplete: response.isComplete)
    }

    /**
     Cancels the connection.

     If you call this, your delegate will receive one more ``Delegate.update(phase:)`` call, if the phase wasn't already an error.
     */
    open func cancel() {
        switch connection.state {
        case .failed, .cancelled:
            break

        default:
            connection.stateUpdateHandler = nil
            connection.cancel()
        }

        // Only update phase if it's not already an error to avoid triggering a Delegate.update call!
        switch phase {
        case .error:
            break

        default:
            phase = .error(error: Errors.cancelled)
        }
    }


    // MARK: Private Methods

    private func update(state: NWConnection.State) {
        switch state {
        case .ready:
            Task {
                var auth = [AuthMethod.noAuthentication]

                if !(user?.isEmpty ?? true) || !(password?.isEmpty ?? true) {
                    auth.append(.usernamePassword)
                }

                await write(.greeting(methods: auth))
            }

        case .failed(let nWError):
            phase = .error(error: nWError)

        case .cancelled:
            phase = .error(error: Errors.cancelled)

        default:
            break
        }

        if case .error = phase {
            cancel()
        }
    }

    private func write(_ message: Message) async {
        do {
            try await connection.send(message.raw)

            switch phase {
            case .greeting:
                switch try await read(.choice) {
                case .authChoice(_, let cauth):
                    if case .noAcceptableMethods = cauth {
                        throw Errors.unaceptableAuthMethods
                    }

                    if case .noAuthentication = cauth {
                        phase = .conn
                        await write(.connRequest(cmd: cmd, host: destHost, port: destPort))
                    }
                    else if case .usernamePassword = cauth {
                        phase = .auth
                        await write(.auth(username: user, password: password))
                    }
                    else {
                        throw Errors.unsupportedAuthMethod
                    }

                default:
                    throw Errors.illegalResponse
                }

            case .auth:
                switch try await read(.auth) {
                case .authResponse(_, let status):
                    if case .failure(let id) = status {
                        throw Errors.authenticationFailure(status: id)
                    }

                    phase = .conn
                    await write(.connRequest(cmd: cmd, host: destHost, port: destPort))

                default:
                    throw Errors.illegalResponse
                }

            case .conn:
                switch try await read(.conn) {
                case .connReply(_, let status, let host, let port):
                    guard case .requestGranted = status else {
                        throw Errors.socksServerError(status: status)
                    }

                    phase = .granted(bndAddr: host, bndPort: port)

                default:
                    throw Errors.illegalResponse
                }

            case .granted:
                // There's no SOCKS5 messaging in this phase!
                break

            case .error(let error):
                throw error
            }
        }
        catch {
            phase = .error(error: error)
            cancel()
        }
    }

    private func read(_ expected: Res) async throws -> Message {
        let (data, isComplete) = try await connection.receive(min: expected.minLength, max: expected.maxLength)

        if isComplete {
            throw Errors.cancelled
        }

        guard let data = data else {
            throw Errors.invalidData
        }

        return try expected.parse(data)
    }

    deinit {
        cancel()
    }
}
