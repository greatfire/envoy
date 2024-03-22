//
//  GlueHandler.swift
//  Envoy_Example
//
//  Created by Benjamin Erhart on 22.03.24.
//  Copyright © 2024 CocoaPods. All rights reserved.
//

import Foundation
import NIOCore

class GlueHandler: ChannelDuplexHandler {

    class func matchedPair() -> (GlueHandler, GlueHandler) {
        let first = GlueHandler()
        let second = GlueHandler()

        first.partner = second
        second.partner = first

        return (first, second)
    }


    private var partner: GlueHandler?

    private var context: ChannelHandlerContext?

    private var pendingRead = false

    private var partnerWritable: Bool {
        context?.channel.isWritable ?? false
    }


    // MARK: ChannelDuplexHandler

    typealias InboundIn = NIOAny
    typealias OutboundIn = NIOAny

    func handlerAdded(context: ChannelHandlerContext) {
        self.context = context
    }

    func handlerRemoved(context: ChannelHandlerContext) {
        self.context = nil
        partner = nil
    }

    func channelRead(context: ChannelHandlerContext, data: NIOAny) {
        partner?.partnerWrite(data)
    }

    func channelReadComplete(context: ChannelHandlerContext) {
        partner?.partnerFlush()
    }

    func channelInactive(context: ChannelHandlerContext) {
        partner?.partnerCloseFull()
    }

    func userInboundEventTriggered(context: ChannelHandlerContext, event: Any) {
        if let event = event as? ChannelEvent, case .inputClosed = event {
            // We have read EOF.
            partner?.partnerWriteEof()
        }
    }

    func errorCaught(context: ChannelHandlerContext, error: any Error) {
        partner?.partnerCloseFull()
    }

    func channelWritabilityChanged(context: ChannelHandlerContext) {
        if context.channel.isWritable {
            partner?.partnerBecameWritable()
        }
    }

    func read(context: ChannelHandlerContext) {
        if partner?.partnerWritable ?? false {
            context.read()
        }
        else {
            pendingRead = true
        }
    }


    // MARK: Private Methods

    private func partnerWrite(_ data: NIOAny) {
        context?.write(data, promise: nil)
    }

    private func partnerFlush() {
        context?.flush()
    }

    private func partnerCloseFull() {
        context?.close(promise: nil)
    }

    private func partnerWriteEof() {
        context?.close(mode: .output, promise: nil)
    }

    private func partnerBecameWritable() {
        if pendingRead {
            pendingRead = false
            context?.read()
        }
    }
}
