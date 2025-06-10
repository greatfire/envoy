package org.greatfire.envoy

import android.util.Log

class EnvoyHysteriaTest(url: String) : EnvoyTest(EnvoyServiceType.HYSTERIA2, url) {
    companion object {
        private const val TAG = "EnvoyHysteriaTest"
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