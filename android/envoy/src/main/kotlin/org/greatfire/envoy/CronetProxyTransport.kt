package org.greatfire.envoy

import android.content.Context

class CronetProxyTransport(url: String) : Transport(EnvoyServiceType.CRONET_PROXY, url) {
    // TODO - support for this is partly implmeneted

    override suspend fun startService(): String {
        return ""
    }

    override fun stopService() {
        // no service
    }

    override suspend fun startTest(context: Context): Boolean {
        return false
    }
}