package org.greatfire.envoy

import android.net.Uri
import android.net.UrlQuerySanitizer
import android.util.Log

class EnvoyV2wechatTest(url: String) : EnvoyTest(EnvoyServiceType.V2WECHAT, url) {
    companion object {
        private const val TAG = "EnvoyV2wechatTest"
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
        return san.getValue("id")
    }
}