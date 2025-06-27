package org.greatfire.envoy

import android.content.Context
import android.net.Uri
import android.util.Log
import IEnvoyProxy.IEnvoyProxy // Go library, we use constants from it here


class HttpEchTransport(url: String) : Transport(EnvoyServiceType.HTTP_ECH, url) {

    // was getEnvoyUrl but returns only ech url? (if available)
    fun getEchUrl(): String {
        // XXX this needs cleanup? get the Envoy URL from IEP
        state.iep?.let {
            return it.echProxyUrl
        }
        Log.e(TAG, "No EchProxyUrl in IEP")
        return ""
    }

    override suspend fun startTest(context: Context): Boolean {
        Log.d(TAG, "Testing Envoy URL with IEnvoyProxy: " + this)

        startService()

        val echUrl = getEchUrl()
        // XXX this is a weird case, IEP returns a new
        // URL to use
        // if it comes back, it's tested and working
        if (url.isNullOrEmpty()) {
            return false
        }

        this.proxyUrl = echUrl
        Log.d(TAG, "IEP Ech URL: " + proxyUrl)
        return true
    }

    override suspend fun startService(): String {
        if (serviceRunning) {
            Log.e(TAG, "Tried to start Http/Ech when it was already running")
            return ""
        }

        Log.d(TAG, "Starting service for Http/Ech")

        serviceRunning = true

        val hostname = Uri.parse(url).getHost()

        hostname?.let {
            val echConfigList = state.dns.getECHConfig(hostname)
            state.iep?.let {
                it.setEnvoyUrl(url, echConfigList)

                it.start(IEnvoyProxy.EnvoyEch, "")
                val addr = it.localAddress(IEnvoyProxy.EnvoyEch)
                return addr
            }
        }

        return ""
    }

    override fun stopService() {
        // stop the associated service
        // this is called to stop unused services

        // this is a no-op, but it might get implemented :)
        state.iep?.let {
            it.stop(IEnvoyProxy.EnvoyEch)
        }
    }
}