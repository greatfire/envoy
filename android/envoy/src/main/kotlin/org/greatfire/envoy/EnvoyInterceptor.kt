package org.greatfire.envoy

import android.util.Log
import okhttp3.*
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI

class EnvoyInterceptor : Interceptor {

    companion object {
        private const val TAG = "EnvoyInterceptor"
    }

    private var proxy: Proxy? = null
    private val state = EnvoyState.getInstance()
    private val util = EnvoyTestUtil.getInstance()

    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {

        val req = chain.request()

        if (util.connected.get()) {
            // MNB: does this mean if a service is set and then overridden
            // to connect directly, no cleanup/restart is needed?
            if (util.service.get() == EnvoyServiceType.DIRECT.ordinal) {
                Log.d(TAG, "Direct: " + req.url)
                // pass the request through
                return chain.proceed(chain.request())
            } else {
                util.activeConnection?.let {
                    // proxy via Envoy
                    Log.d(TAG, "Proxy Via Envoy: " + it.testType)
                    val res = when (it.testType) {
                        EnvoyServiceType.CRONET_ENVOY -> {
                            Log.d(TAG, "Cronet request to Envoy server")
                            cronetToEnvoy(chain)
                        }
                        EnvoyServiceType.OKHTTP_ENVOY -> {
                            Log.d(TAG, "OkHttp request to Envoy server")
                            okHttpToEnvoy(chain)
                        }
                        EnvoyServiceType.OKHTTP_PROXY,
                        // all of these services provive a standard SOCKS5
                        // interface. We used to pass these through Cronet,
                        // should we test both? Just use OkHttp for now
                        EnvoyServiceType.HYSTERIA2,
                        EnvoyServiceType.V2WS,
                        EnvoyServiceType.V2SRTP,
                        EnvoyServiceType.V2WECHAT,
                        EnvoyServiceType.SHADOWSOCKS, -> {
                            Log.d(TAG, "Passing request to standard proxy")
                            useStandardProxy(chain)
                        }
                        else -> {
                            Log.e(TAG, "unsupported activeType: " + it.testType)
                            // pass the request through and hope for the best?
                            chain.proceed(chain.request())
                        }
                    }
                    return res
                }

                Log.e(TAG, "envoyConnected is true, but activeConnection is not set!")
                // try the request as-is 🤷
                return chain.proceed(chain.request())
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
            if (EnvoyNetworking.initialized && EnvoyNetworking.passivelyTestDirect) {
                // XXX is a single 200 enough to say it's working?
                Log.i(TAG, "Direct connections appear to be working, disabling Envoy")
                // XXX we probably shouldn't need to make an EnvoyTest
                // instance here :)
                // MNB calling stopTestPassed shouldn't have unintended consequences
                // connectIfNeeded will do nothing, so don't bother calling
                util.stopTestPassed(EnvoyTest(EnvoyServiceType.DIRECT, "direct://"))
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
            url(util.activeConnection!!.url + "?test=" + t)
        }

        return requestBuilder.build()
    }


    // Proxy though the Envoy HTTPS proxy
    //
    // XXX socks5? some services require the Envoy rewrites AND a
    // SOCKS proxy
    private fun okHttpToEnvoy(chain: Interceptor.Chain): Response {
        val origRequest = chain.request()

        // XXX envoy:// URL headers

        Log.d(TAG, "okHttpToEnvoy: " + origRequest.url)

        return chain.proceed(getEnvoyRequest(origRequest))
    }

    private fun setupProxy() {
        val uri = URI(util.activeConnection!!.proxyUrl)
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

        // XXX envoy:// URL headers

        val callback = CronetUrlRequestCallback(req, chain.call())
        val urlRequest = CronetNetworking.buildRequest(
            req, callback, state.cronetEngine!!
        )
        urlRequest.start()
        return callback.blockForResponse()
    }
}
