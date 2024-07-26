//
//  Network+Helpers.swift
//  Envoy
//
//  Created by Benjamin Erhart on 11.06.24.
//  Copyright © 2024 GreatFire. Licensed under Apache-2.0.
//

import Foundation
import Network

extension NWEndpoint.Host {

    var socks5: Data {
        var data = Data()

        switch self {
        case .name(let string, _):
            if let name = string.data(using: .utf8, allowLossyConversion: true) {
                data.append(3)
                data.append(UInt8(name.count))
                data.append(contentsOf: name[0 ..< min(name.endIndex, 255)])
            }

        case .ipv4(let iPv4Address):
            data.append(1)
            data.append(contentsOf: iPv4Address.rawValue)

        case .ipv6(let iPv6Address):
            data.append(4)
            data.append(contentsOf: iPv6Address.rawValue)

        @unknown default:
            break
        }

        return data
    }

    static func from(socks5 data: Data) -> NWEndpoint.Host? {
        switch data.first {
        case 1:
            if let address = IPv4Address(data[1...4], nil) {
                return .ipv4(address)
            }

        case 3:
            if let string = String(data: data[1...], encoding: .utf8) {
                return .name(string, nil)
            }

        case 4:
            if let address = IPv6Address(data[1...16], nil) {
                return .ipv6(address)
            }

        default:
            break
        }

        return nil
    }
}

extension NWEndpoint.Port {

    var socks5: Data {
        var source = rawValue.bigEndian

        return Data(bytes: &source, count: MemoryLayout<UInt16>.size)
    }

    init?(socks5 data: Data) {
        if data.count < 2 {
            return nil
        }

        self.init(rawValue: data.withUnsafeBytes({ $0.load(as: UInt16.self) }))
    }
}

extension NWConnection {

    func receive(min: Int = 0, max: Int = .max) async throws -> (content: Data?, isComplete: Bool) {
        return try await withCheckedThrowingContinuation { continuation in
            let callback = { @Sendable (content: Data?, contentContext: NWConnection.ContentContext?, isComplete: Bool, error: NWError?) in
                if let error = error {
                    return continuation.resume(throwing: error)
                }

                continuation.resume(returning: (content, isComplete))
            }

            receive(minimumIncompleteLength: min, maximumLength: max, completion: callback)
        }
    }

    func send(_ data: Data?) async throws {
        return try await withCheckedThrowingContinuation { continuation in
            send(content: data,
                 contentContext: data == nil ? .finalMessage : .defaultMessage,
                 completion: .contentProcessed(
                    { error in
                        if let error = error {
                            return continuation.resume(throwing: error)
                        }

                        continuation.resume()
                    }))
        }
    }
}
