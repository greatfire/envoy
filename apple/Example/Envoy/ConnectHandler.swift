//
//  ConnectHandler.swift
//  Envoy_Example
//
//  Created by Benjamin Erhart on 22.03.24.
//  Copyright © 2024 CocoaPods. All rights reserved.
//

import Foundation
import NIOCore
import NIOHTTP1
import NIOPosix
import os

class ConnectHandler: ChannelInboundHandler, RemovableChannelHandler {

    enum State {
        case idle
        case beganConnecting
        case awaitingConnection(pendingBytes: [NIOAny])
        case awaitingEnd(peerChannel: Channel)
        case upgradeComplete(pendingBytes: [NIOAny])
        case upgradeFailed
    }


    private var upgradeState: State = .idle

    private let logger: Logger


    init(logger: Logger) {
        self.logger = logger
    }

    typealias OutboundOut = HTTPServerResponsePart


    // MARK: ChannelInboundHandler

    typealias InboundIn = HTTPServerRequestPart

    func channelRead(context: ChannelHandlerContext, data: NIOAny) {
        switch upgradeState {
        case .idle:
            handleInitialMessage(context, data: unwrapInboundIn(data))

        case .beganConnecting:
            // We got .end, we're still waiting on the connection.
            if case .end = unwrapInboundIn(data) {
                upgradeState = .awaitingConnection(pendingBytes: [])
                removeDecoder(context)
            }

        case .awaitingConnection(var pendingBytes):
            // We've seen end, this must not be HTTP anymore. Do not unwrap.
            upgradeState = .awaitingConnection(pendingBytes: [])
            pendingBytes.append(data)
            upgradeState = .awaitingConnection(pendingBytes: pendingBytes)

        case .awaitingEnd(let peerChannel):
            if case .end = unwrapInboundIn(data) {
                // Upgrade has completed!
                upgradeState = .upgradeComplete(pendingBytes: [])
                removeDecoder(context)
                glue(peerChannel, context)
            }

        case .upgradeComplete(var pendingBytes):
            // We're currently delivering data, keep doing so.
            upgradeState = .upgradeComplete(pendingBytes: [])
            pendingBytes.append(data)
            upgradeState = .upgradeComplete(pendingBytes: pendingBytes)

        case .upgradeFailed:
            break
        }
    }


    // MARK: RemovableChannelHandler

    func removeHandler(context: NIOCore.ChannelHandlerContext, removalToken: NIOCore.ChannelHandlerContext.RemovalToken) {
        var didRead = false

        // Deliver any pending bytes before getting removed.
        while case .upgradeComplete(var pendingBytes) = upgradeState, pendingBytes.count > 0 {

            // Avoid a CoW while we pull some data out.
            upgradeState = .upgradeComplete(pendingBytes: [])
            let nextRead = pendingBytes.removeFirst()
            upgradeState = .upgradeComplete(pendingBytes: pendingBytes)

            context.fireChannelRead(nextRead)
            didRead = true
        }

        if didRead {
            context.fireChannelReadComplete()
        }

        logger.debug("Removing ourselves from the pipeline.")

        context.leavePipeline(removalToken: removalToken)
    }


    // MARK: Private Methods

    private func handleInitialMessage(_ context: ChannelHandlerContext, data: InboundIn) {
        guard case .head(let head) = data else {
            logger.error("Invalid HTTP message type")
            httpErrorAndClose(context)

            return
        }

        logger.info("\(head.method.rawValue) \(head.uri) \(head.version) \(head.headers)")

        guard head.method == .CONNECT else {
            logger.error("Invalid HTTP method: \(head.method.rawValue)")
            httpErrorAndClose(context)

            return
        }

        let parts = head.uri.split(separator: ":", maxSplits: 1, omittingEmptySubsequences: false)

        guard let host = parts.first,
              !host.isEmpty
        else {
            logger.error("Invalid HTTP URL: \(head.uri)")
            httpErrorAndClose(context)

            return
        }

        let port = parts.last.flatMap { Int($0, radix: 10) } ?? 80

        upgradeState = .beganConnecting

        logger.info("Connecting to \(host):\(port)")

        let channelFuture = ClientBootstrap(group: context.eventLoop)
            .connect(host: String(host), port: port)

        channelFuture.whenSuccess { channel in
            self.logger.info("Connected to \(channel.remoteAddress?.description ?? "(nil)")")

            switch self.upgradeState {
            case .beganConnecting:
                self.upgradeState = .awaitingEnd(peerChannel: channel)

            case .awaitingConnection(let pendingBytes):
                self.upgradeState = .upgradeComplete(pendingBytes: pendingBytes)
                self.glue(channel, context)

            case .awaitingEnd(let peerChannel):
                // Logic error: Close already connected peer channel.
                peerChannel.close(mode: .all, promise: nil)
                context.close(promise: nil)

            case .idle, .upgradeComplete, .upgradeFailed:
                // Logic error: Shut down the connection.
                context.close(promise: nil)
            }
        }

        channelFuture.whenFailure { error in
            self.logger.error("Connection failed: \(error)")

            switch self.upgradeState {
            case .beganConnecting, .awaitingConnection:
                // There's an open HTTP connection. Report error.
                self.httpErrorAndClose(context)

            case .awaitingEnd(let peerChannel):
                // Logic error. Close already connected peer channel.
                peerChannel.close(mode: .all, promise: nil)
                context.close(promise: nil)

            case .idle, .upgradeComplete, .upgradeFailed:
                // Logic error: Shut down the connection.
                context.close(promise: nil)
            }

            context.fireErrorCaught(error)
        }
    }

    private func glue(_ peerChannel: Channel, _ context: ChannelHandlerContext) {
        logger.debug("Gluing together \(ObjectIdentifier(context.channel).debugDescription) and \(ObjectIdentifier(peerChannel).debugDescription)")

        // NIO insists that we set a Content-Length header, even though we shouldn't on a HEAD response.
        let headers = HTTPHeaders([("Content-Length", "0")])
        let head = HTTPResponseHead(version: .http1_1, status: .ok, headers: headers)

        context.write(wrapOutboundOut(.head(head)), promise: nil)
        context.writeAndFlush(wrapOutboundOut(.end(nil)), promise: nil)

        // Remove the HTTP encoder.
        context.pipeline.context(handlerType: HTTPResponseEncoder.self).whenSuccess {
            context.pipeline.removeHandler(context: $0, promise: nil)
        }

        let (localGlue, peerGlue) = GlueHandler.matchedPair()

        context.channel.pipeline.addHandler(localGlue).and(peerChannel.pipeline.addHandler(peerGlue)).whenComplete { result in
            switch result {
            case .success(_):
                context.pipeline.removeHandler(self, promise: nil)

            case .failure(_):
                // Order is important: Close connected peer channel before closing our channel.
                peerChannel.close(mode: .all, promise: nil)
                context.close(promise: nil)
            }
        }
    }

    private func httpErrorAndClose(_ context: ChannelHandlerContext) {
        upgradeState = .upgradeFailed

        let headers = HTTPHeaders([("Content-Length", "0"), ("Connection", "close")])
        let head = HTTPResponseHead(version: .http1_1, status: .badRequest, headers: headers)

        context.write(wrapOutboundOut(.head(head)), promise: nil)
        context.writeAndFlush(wrapOutboundOut(.end(nil))).whenComplete { _ in
            context.close(mode: .output, promise: nil)
        }
    }

    private func removeDecoder(_ context: ChannelHandlerContext) {
        context.pipeline.context(handlerType: ByteToMessageHandler<HTTPRequestDecoder>.self).whenSuccess {
            context.pipeline.removeHandler(context: $0, promise: nil)
        }
    }
}
