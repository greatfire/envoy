package org.greatfire.envoy.transport

import org.greatfire.envoy.EnvoyConnectionTests
import org.greatfire.envoy.EnvoyTransportType

import android.net.Uri
import android.net.UrlQuerySanitizer
import android.util.Log

import IEnvoyProxy.IEnvoyProxy
import android.content.Context

class V2WechatTransport(url: String) : Transport(EnvoyTransportType.V2WECHAT, url) {

    override suspend fun startTest(context: Context): Boolean {
        val addr = startService()

        if (addr.isEmpty()) {
            // The go code doesn't handle failures well, but an empty
            // string here indicates failure
            stopService()
            return false
        }

        proxyUrl = "socks5://$addr"
        Log.d(TAG, "testing V2Ray WeChat at ${proxyUrl}")

        val res = testStandardProxy(Uri.parse(proxyUrl), testResponseCode)
        if (res == false) {
            stopService()
        }
        return res
    }

    override suspend fun startService(): String {
        if (serviceRunning) {
            Log.e(TAG, "Tried to start V2ray Wechat when it was already running")
            return ""
        }

        Log.d(TAG, "Starting service for V2ray Wechat")

        serviceRunning = true

        state.iep?.let {
            val server = Uri.parse(url)

            it.v2RayServerAddress = server.host
            it.v2RayServerPort = server.port.toString()
            it.v2RayId = getV2RayUuid(url)

            val host = server.host
            val port = server.port.toString()
            val uuid = getV2RayUuid(url)

            it.start(IEnvoyProxy.V2RayWechat, "")
            val addr = it.localAddress(IEnvoyProxy.V2RayWechat)
            EnvoyConnectionTests.isItUpYet(addr)
            return addr
        }

        return ""
    }

    override fun stopService() {
        // stop the associated service
        // this is called to stop unused services

        state.iep?.let {
            it.stop(IEnvoyProxy.V2RayWechat)
        }
    }

    private fun getV2RayUuid(url: String): String {
        val san = UrlQuerySanitizer()
        san.setAllowUnregisteredParamaters(true)
        san.parseUrl(url)
        if (san.hasParameter("id")) {
            return san.getValue("id")
        }
        Log.e(TAG, "V2Ray WeChat URL doesn't have a UUID")
        return ""
    }
}
