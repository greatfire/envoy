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
        if (EnvoyNetworking.envoyConnected) {
            if (EnvoyNetworking.useDirect) {
                // pass the request through
                return chain.proceed(chain.request())
            } else {
                // proxy via Envoy
                val res = when (EnvoyNetworking.activeType) {
                    ENVOY_ACTIVE_OKHTTP_ENVOY -> {
                        proxyToEnvoy(chain)
                    }
                    ENVOY_ACTIVE_OKHTTP_PROXY -> {
                        useStandardProxy(chain)
                    }
                    else -> {
                        Log.e(TAG, "unsupported activeType: " + EnvoyNetworking.activeType)
                        // pass the request through
                        chain.proceed(chain.request())
                    }
                }

                // Log.d(TAG, "")

                return res
            }
        } else {
            // let requests pass though and see record if they succeed
            // failures are likely to be timeouts, so don't wait for that
            return observingInterceptor(chain)
        }
    }

    private fun observingInterceptor(chain: Interceptor.Chain): Response {
        val req = chain.request()

        // Log.d(TAG, "obs URL: " + req.url)

        val res = chain.proceed(req)

        if (res.isSuccessful) {
            // signal that things appear to be working without our help
            EnvoyNetworking.appConnectionsWorking = true
        }

        // Log.d(TAG, "obs code: " + res.code)

        return res
    }

    // Proxy though the Envoy HTTPS proxy
    //
    // XXX socks5?
    private fun proxyToEnvoy(chain: Interceptor.Chain): Response {
        val origRequest = chain.request()
        val requestBuilder = origRequest.newBuilder()

        val t = System.currentTimeMillis()
        val url = origRequest.url

        Log.d(TAG, "proxyToEnvoy: " + url)
        // Log.d(TAG, "headers:" + origRequest.headers)

        with (requestBuilder) {
            addHeader("X-Envoy", "Interceptor")
            addHeader("Host-Orig", url.host)
            addHeader("Url-Orig", url.toString())
            // XXX do the cache param correctly
            url(EnvoyNetworking.activeUrl + "?test=" + t)
        }

        val resp = chain.proceed(requestBuilder.build())

        Log.d(TAG, "proxyToEnvoy code: " + resp.code)

        return resp
    }

    private fun setupProxy() {
        val uri = URI(EnvoyNetworking.activeUrl)
        var proxyType = Proxy.Type.HTTP
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

    /*
    // Proxy to SOCKS5 without the Envoy rewrites
    private fun proxyViaSocks5(chain: Interceptor.Chain, proxy: String): Response {

        val requestBuilder = chain.request().newBuilder()

        val purl = URL(proxy)

        var proxyType: Proxy.Type

        val proto = purl.getProtocol()
        if (proto == "socks5") {
            proxyType = Proxy.Type.SOCKS
        } else if(proto == "http" || proto == "https") {
            proxyType = Proxy.Type.HTTP
        } else {
            Log.e(TAG, "Unsupported proxy protocol" + proto)
            // Throw an error?
            proxyType = Proxy.Type.DIRECT
        }
        val addr = InetSocketAddress(purl.getHost(), purl.getPort())
        val proxy = Proxy(proxyType, addr)

        requestBuilder.proxy(proxy)

        return chain.proceed(requestBuilder.build())
    }

     */
}
