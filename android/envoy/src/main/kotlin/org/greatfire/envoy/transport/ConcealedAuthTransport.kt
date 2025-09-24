package org.greatfire.envoy.transport

import org.greatfire.envoy.EnvoyTransportType

class ConcealedAuthTransport(url: String) : Transport(EnvoyTransportType.HTTPCA_ENVOY, url) {
    override suspend fun startTest(context: Context): Boolean {
        val request = OkHttpEnvoyTransport.envoyProxyRewrite(
            Request.Builder(), url, testUrl, salt).build()

        val temp = runTest(request)
        if (temp == true && this.proxyUrl.isNullOrEmpty()) {
            this.proxyUrl = url
        }
        return temp
    }

    override fun stopService() {
        // No service to stop
    }
}