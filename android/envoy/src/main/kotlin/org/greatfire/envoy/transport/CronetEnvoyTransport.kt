package org.greatfire.envoy.transport

import org.greatfire.envoy.CronetNetworking
import org.greatfire.envoy.EnvoyServiceType

import android.content.Context
import android.net.Uri
import android.util.Log
import java.util.concurrent.Executors

class CronetEnvoyTransport(url: String) : Transport(EnvoyServiceType.CRONET_ENVOY, url) {

    override suspend fun startTest(context: Context): Boolean {
        // proxyUrl is assumed to be an Envoy proxy because this is a Cronet Envoy test
        // XXX this is a bug, we need to support both
        // proxyUrl might be an Envoy proxy or a standard (socks/http) proxy

        val cronetEngine = CronetNetworking.buildEngine(
            context = context,
            // cacheFolder = "", // no cache XXX? this is the default
            // XXX this needs to support standard proxies WITH the
            // Envoy rewrites.. currently proxyUrl does both jobs
            // proxyUrl = this.proxyUrl,
            resolverRules = resolverRules,
            cacheSize = 0, // cache size in MB
        )
        val callback = TestUrlRequestCallback()
        // aim at the Envoy proxy instead of the real target
        val requestBuilder = cronetEngine.newUrlRequestBuilder(
            url, // Envoy proxy URL
            callback,
            Executors.newCachedThreadPool()
        )
        // add the Envoy headers for the real target
        val targetHost = Uri.parse(Transport.testUrl).host
        // XXX cache param
        requestBuilder.addHeader("Host-Orig", targetHost)
        requestBuilder.addHeader("Url-Orig", Transport.testUrl)

        val request = requestBuilder.build()
        request.start()
        callback.blockForResponse()

        // test is now complete
        val temp = callback.succeeded
        if (temp == true && proxyUrl.isNullOrEmpty()) {
            // the Envoy proxy URL needs to be copied to proxyUrl
            proxyUrl = url
        }
        return temp
    }

    override fun stopService() {
        // no service to stop
    }
}
