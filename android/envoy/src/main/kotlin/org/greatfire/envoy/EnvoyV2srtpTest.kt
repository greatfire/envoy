package org.greatfire.envoy

import android.net.Uri
import android.net.UrlQuerySanitizer
import android.util.Log

import IEnvoyProxy.IEnvoyProxy
import android.content.Context

class EnvoyV2srtpTest(envoyUrl: String, testUrl: String, testResponseCode: Int) : EnvoyTest(EnvoyServiceType.V2SRTP, envoyUrl, testUrl, testResponseCode) {

    override suspend fun startTest(context: Context): Boolean {
        var addr = startService()

        if (addr == "") {
            // The go code doesn't handle failures well, but an empty
            // string here indicates failure
            stopService()
            return false
        }

        proxyUrl = "socks5://$addr"
        Log.d(TAG, "Testing V2Ray SRTP ${proxyUrl}")

        val res = testStandardProxy(Uri.parse(proxyUrl), testResponseCode)
        if (res == false) {
            stopService()
        }
        return res
    }

    override suspend fun startService(): String {
        if (serviceRunning) {
            Log.e(TAG, "Tried to start V2ray Srtp when it was already running")
            return ""
        }

        Log.d(TAG, "Starting service for V2ray Srtp")

        serviceRunning = true

        state.iep?.let {
            val server = Uri.parse(url)

            it.v2RayServerAddress = server.host
            it.v2RayServerPort = server.port.toString()
            it.v2RayId = getV2RayUuid(url)

            it.start(IEnvoyProxy.V2RaySrtp, "")
            val addr = it.localAddress(IEnvoyProxy.V2RaySrtp)
            EnvoyConnectionTests.isItUpYet(addr)
            return addr
        }

        return ""
    }

    override fun stopService() {
        // stop the associated service
        // this is called to stop unused services

        state.iep?.let {
            it.stop(IEnvoyProxy.V2RaySrtp)
        }
    }

    private fun getV2RayUuid(url: String): String {
        val san = UrlQuerySanitizer()
        san.setAllowUnregisteredParamaters(true)
        san.parseUrl(url)
        return san.getValue("id")
    }
}