package org.greatfire.envoy.transport

import org.greatfire.envoy.EnvoyServiceType

import IEnvoyProxy.IEnvoyProxy
import android.content.Context

class CronetMasqueTransport(url: String) : Transport(EnvoyServiceType.CRONET_MASQUE, url) {

    override suspend fun startTest(context: Context): Boolean {
        // Not implemented, does cronet support MASQUE (yet?)
        return false
    }

    override suspend fun startService(): String {
        // no service for this
        return ""
    }

    override fun stopService() {
        // no service for this
    }
}
