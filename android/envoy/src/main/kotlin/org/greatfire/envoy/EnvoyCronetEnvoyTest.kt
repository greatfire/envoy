package org.greatfire.envoy

import android.content.Context
import android.net.Uri
import java.util.concurrent.Executors

class EnvoyCronetEnvoyTest(envoyUrl: String, testUrl: String, testResponseCode: Int) : EnvoyTest(EnvoyServiceType.CRONET_ENVOY, envoyUrl, testUrl, testResponseCode) {
    companion object {
        private const val TAG = "EnvoyCronetEnvoyTest"
    }

    override suspend fun startTest(context: Context): Boolean {
        // proxyUrl is assumed to be an Envoy proxy because this is a Cronet Envoy test
        // proxyUrl might be an Envoy proxy or a standard (socks/http) proxy

        val cronetEngine = CronetNetworking.buildEngine(
            context = context,
            cacheFolder = null, // no cache XXX?
            proxyUrl = proxyUrl,
            resolverRules = resolverRules,
            cacheSize = 0, // cache size in MB
        )
        val callback = TestUrlRequestCallback()
        // aim at the Envoy proxy instead of the real target
        val requestBuilder = cronetEngine.newUrlRequestBuilder(
            envoyUrl, // Envoy proxy URL
            callback,
            Executors.newCachedThreadPool()
        )
        // add the Envoy headers for the real target
        val targetHost = Uri.parse(EnvoyConnectionTests.testUrl).host
        // XXX cache param
        requestBuilder.addHeader("Host-Orig", targetHost)
        requestBuilder.addHeader("Url-Orig", EnvoyConnectionTests.testUrl)

        val request = requestBuilder.build()
        request.start()
        callback.blockForResponse()

        // test is now complete
        return callback.succeeded
    }
}