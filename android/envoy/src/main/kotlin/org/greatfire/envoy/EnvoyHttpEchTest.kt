package org.greatfire.envoy

import android.net.Uri
import android.util.Log

class EnvoyHttpEchTest(url: String) : EnvoyTest(EnvoyServiceType.HTTP_ECH, url) {
    companion object {
        private const val TAG = "EnvoyHttpEchTest"
    }

    override suspend fun startService(): String {
        if (serviceRunning) {
            Log.e(TAG, "Tried to start Http/Ech when it was already running")
            return ""
        }

        Log.d(TAG, "Starting service for Http/Ech")

        serviceRunning = true

        val hostname = Uri.parse(url).getHost()

        // XXX set DOH server for Go code here?
        // or keep it in connect()?

        hostname?.let {
            val echConfigList = state.dns.getECHConfig(hostname)
            state.iep?.let {
                it.setEnvoyUrl(url, echConfigList)
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