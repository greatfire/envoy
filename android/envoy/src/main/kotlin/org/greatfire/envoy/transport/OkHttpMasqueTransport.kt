package org.greatfire.envoy.transport

import org.greatfire.envoy.EnvoyServiceType

import android.net.Uri
import android.util.Log

import IEnvoyProxy.IEnvoyProxy
import android.content.Context

class OkHttpMasqueTransport(url: String) : Transport(EnvoyServiceType.OKHTTP_MASQUE, url) {

    override suspend fun startTest(context: Context): Boolean {
        if (proxyUrl == null) {
            // the other test hasn't started it yet
            val addr = startService()
            proxyUrl = "http://$addr"
            Log.d(TAG, "Starting MASQUE: $addr")
        }

        return testStandardProxy(Uri.parse(proxyUrl), testResponseCode)
    }

    override suspend fun startService(): String {
        if (serviceRunning) {
            Log.e(TAG, "Tried to start Masque when it was already running")
            return ""
        }

        Log.d(TAG, "Starting service for Masque")

        serviceRunning = true

        Log.d(TAG, "about to start MASQUE 👺")

        val upstreamUri = Uri.parse(url)
        if (upstreamUri.host == null) {
            Log.e(TAG, "MASQUE host is null!?")
            return ""
        }

        state.iep?.let {
            it.masqueHost = upstreamUri.host

            var upstreamPort = upstreamUri.port
            if (upstreamPort == -1) {
                upstreamPort = 443
            }
            it.masquePort = upstreamPort.toLong()

            it.start(IEnvoyProxy.Masque, "")
            val addr = it.localAddress(IEnvoyProxy.Masque)
            return addr
        }

        return ""
    }

    override fun stopService() {
        // stop the associated service
        // this is called to stop unused services

        // this is currently a no-op, but call it in case it ever isn't :)
        state.iep?.let {
            it.stop(IEnvoyProxy.Masque)
        }
    }
}
