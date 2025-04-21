package org.greatfire.envoy

import android.util.Log
import okhttp3.*
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
// import java.net.URL
import java.net.URI


class EnvoyInterceptor : Interceptor {

    companion object {
        private const val TAG = "EnvoyInterceptor"
    }

    private var proxy: Proxy? = null

    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()

        if (EnvoyNetworking.envoyConnected) {
            if (EnvoyNetworking.useDirect) {
                Log.d(TAG, "Direct: " + req.url)
                // pass the request through
                return chain.proceed(chain.request())
            } else {
                // proxy via Envoy
                Log.d(TAG, "Proxy Via Envoy: " + EnvoyNetworking.activeType)
                val res = when (EnvoyNetworking.activeType) {
                    EnvoyServiceType.CRONET_ENVOY -> {
                        Log.d(TAG, "Cronet request to Envoy server")
                        cronetToEnvoy(chain)
                    }
                    EnvoyServiceType.OKHTTP_ENVOY -> {
                        Log.d(TAG, "OkHttp request to Envoy server")
                        okHttpToEnvoy(chain)
                    }
                    EnvoyServiceType.OKHTTP_PROXY -> {
                        Log.d(TAG, "Passing request to standard proxy")
                        useStandardProxy(chain)
                    }
                    else -> {
                        Log.e(TAG, "unsupported activeType: " + EnvoyNetworking.activeType)
                        // MNB: should this be an error state?
                        // It's a bug if this happens. Rather than error out
                        // the request, we might as well try a direct connection

                        // pass the request through and hope for the best?
                        chain.proceed(chain.request())
                    }
                }

                return res
            }
        } else {
            // let requests pass though and see record if they succeed
            // failures are likely to be timeouts, so don't wait for that
            return observingInterceptor(chain)
        }
    }

    private fun observingInterceptor(chain: Interceptor.Chain): Response {
        val res = chain.proceed(chain.request())

        if (res.isSuccessful) {
            // signal that things appear to be working without our help
            EnvoyNetworking.appConnectionsWorking = true
            if (EnvoyNetworking.passivelyTestDirect) {
                // XXX is a single 200 enough to say it's working?
                Log.i(TAG, "Direct connections appear to be working, disabling Envoy")
                // XXX we probably shouldn't need to make an EnvoyTest
                // instance here :)
                //
                // the URL param is not used for direct connctions
                EnvoyNetworking.connected(EnvoyTest(
                    EnvoyServiceType.DIRECT, "direct://"))
            }
        }

        return res
    }

    // Given an OkHttp Request, return a new one pointed at the Envoy
    // URL with the original request moved in to headers
    private fun getEnvoyRequest(origRequest: Request): Request {
        val requestBuilder = origRequest.newBuilder()

        val t = System.currentTimeMillis()
        val url = origRequest.url

        with (requestBuilder) {
            // addHeader("X-Envoy", "Interceptor")
            addHeader("Host-Orig", url.host)
            addHeader("Url-Orig", url.toString())
            // XXX do the cache param correctly
            url(EnvoyNetworking.activeUrl + "?test=" + t)
        }

        return requestBuilder.build()
    }


    // Proxy though the Envoy HTTPS proxy
    //
    // XXX socks5? some services require the Envoy rewrites AND a
    // SOCKS proxy
    private fun okHttpToEnvoy(chain: Interceptor.Chain): Response {
        val origRequest = chain.request()

        Log.d(TAG, "okHttpToEnvoy: " + origRequest.url)

        return chain.proceed(getEnvoyRequest(origRequest))
    }

    private fun setupProxy() {
        val uri = URI(EnvoyNetworking.activeUrl)
        var proxyType = Proxy.Type.HTTP
        val scheme = uri.getScheme()
        Log.d(TAG, "SCHEME: $scheme")
        if (uri.getScheme() == "socks5") {
            proxyType = Proxy.Type.SOCKS
        }
        Log.d(TAG, "creating proxy: " + proxyType)
        proxy = Proxy(proxyType, InetSocketAddress(uri.getHost(), uri.getPort()))
    }

    private fun useStandardProxy(chain: Interceptor.Chain): Response {
        // I can't find a better way to do this than building a new
        // client with the proxy config
        if (proxy == null) {
            setupProxy()
        }

        val client = OkHttpClient.Builder().proxy(proxy).build()

        Log.d(TAG, "Standard Proxy Request: ")

        val req = chain.request().newBuilder().build()
        return client.newCall(req).execute()
    }

    // Use cronet to make the request to an Envoy proxy
    private fun cronetToEnvoy(chain: Interceptor.Chain): Response {

        val req = getEnvoyRequest(chain.request())

        val callback = CronetUrlRequestCallback(req, chain.call())
        val urlRequest = CronetNetworking.buildRequest(
            req, callback, EnvoyNetworking.cronetEngine!!
        )
        urlRequest.start()
        return callback.blockForResponse()
    }
}
