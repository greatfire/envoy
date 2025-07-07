package org.greatfire.envoy.transport

import org.greatfire.envoy.EnvoyConnectionTests
import org.greatfire.envoy.EnvoyTransportType

import android.util.Log

import IEnvoyProxy.IEnvoyProxy
import android.content.Context
import android.net.Uri

class Hysteria2Transport(url: String) : Transport(EnvoyTransportType.HYSTERIA2, url) {

    override suspend fun startTest(context: Context): Boolean {
        val addr = startService()

        proxyUrl = "socks5://$addr"

        Log.d(TAG, "testing hysteria2 at ${proxyUrl}")

        val res = testStandardProxy(Uri.parse(proxyUrl), testResponseCode)
        if (res == false) {
            stopService()
        }
        return res
    }

    override suspend fun startService(): String {
        if (serviceRunning) {
            Log.e(TAG, "Tried to start Hysteria when it was already running")
            return ""
        }

        Log.d(TAG, "Starting service for Hysteria")

        serviceRunning = true

        state.iep?.let {
            it.hysteria2Server = url
            it.start(IEnvoyProxy.Hysteria2, "")
            val addr = it.localAddress(IEnvoyProxy.Hysteria2)
            EnvoyConnectionTests.isItUpYet(addr)
            return addr
        }

        return ""
    }

    override fun stopService() {
        // stop the associated service
        // this is called to stop unused services

        state.iep?.let {
            it.stop(IEnvoyProxy.Hysteria2)
        }
    }
}
