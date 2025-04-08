package org.greatfire.envoy

import android.util.Log
import okhttp3.*
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL


class EnvoyInterceptor : Interceptor {

    companion object {
        private const val TAG = "EnvoyInterceptor"
    }

//    var envoyUrl: String = "";

    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        // return proxyToEnvoy(chain)
        return observingInterceptor(chain)
    }

    private fun observingInterceptor(chain: Interceptor.Chain): Response {
        val req = chain.request()

        Log.d(TAG, "request URL: " + req.url)
        Log.d(TAG, "I Thread:" + Thread.currentThread().name)

        val resp = chain.proceed(req)

        Log.d(TAG, "resp: " + resp.code + " from: " + req.url)

        return resp
    }

    // Proxy though the Envoy HTTPS proxy
    //
    // XXX socks5?
    private fun proxyToEnvoy(chain: Interceptor.Chain): Response {
        val origRequest = chain.request()
        val requestBuilder = origRequest.newBuilder()

        val t = System.currentTimeMillis()
        val url = origRequest.url

        Log.d(TAG, "request: " + url)
        // Log.d(TAG, "headers:" + origRequest.headers)

        with (requestBuilder) {
            addHeader("X-Envoy", "Interceptor")
            addHeader("Host-Orig", url.host)
            addHeader("Url-Orig", url.toString())
            // XXX do the cache param correctly
            url("https://localhost/?test=" + t)
        }

        val resp = chain.proceed(requestBuilder.build())

        Log.d(TAG, "response code: " + resp.code)

        return resp
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
