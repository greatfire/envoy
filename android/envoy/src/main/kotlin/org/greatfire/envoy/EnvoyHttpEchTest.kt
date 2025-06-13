package org.greatfire.envoy

import android.net.Uri
import android.util.Log

import android.content.Context

class EnvoyHttpEchTest(envoyUrl: String, testUrl: String, testResponseCode: Int) : EnvoyTest(EnvoyServiceType.HTTP_ECH, envoyUrl, testUrl, testResponseCode) {
    companion object {
        private const val TAG = "EnvoyHttpEchTest"
    }

    override suspend fun startTest(context: Context): Boolean {
        Log.d(TAG, "Testing Envoy URL with IEnvoyProxy: " + this)

        startService()
        // XXX this is a weird case, IEP returns a new
        // URL to use
        // if it comes back, it's tested and working
        val url = getEchUrl()

        if (url.isNullOrEmpty()) {
            return false
        }

        proxyUrl = url
        proxyIsEnvoy = true
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

        val hostname = Uri.parse(envoyUrl).getHost()

        // XXX set DOH server for Go code here?
        // or keep it in connect()?

        hostname?.let {
            val echConfigList = state.dns.getECHConfig(hostname)
            state.iep?.let {
                it.setEnvoyUrl(envoyUrl, echConfigList)
            }
        }

        return ""
    }

    override fun stopService() {
        // stop the associated service
        // this is called to stop unused services

        Log.d(TAG, "TODO: Need method to stop Http/Ech")
    }
}