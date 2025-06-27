package org.greatfire.envoy

import android.net.Uri
import android.util.Log
import okhttp3.*
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.SocketTimeoutException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

class EnvoyInterceptor : Interceptor {

    companion object {
        private const val TAG = "EnvoyInterceptor"
    }

    private var proxy: Proxy? = null
    private val state = EnvoyState.getInstance()

    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {

        val req = chain.request()

        if (state.connected.get()) {
            if (state.activeServiceType.get() == EnvoyServiceType.DIRECT.ordinal) {
                Log.d(TAG, "Direct: " + req.url)
                // pass the request through
                return chain.proceed(chain.request())
            } else {
                state.activeService?.let {
                    // proxy via Envoy
                    Log.d(TAG, "Proxy Via Envoy: " + it.testType)
                    val res = when (it.testType) {
                        EnvoyServiceType.CRONET_ENVOY -> {
                            Log.d(TAG, "Cronet request to Envoy server")
                            cronetToEnvoy(chain)
                        }
                        EnvoyServiceType.OKHTTP_ENVOY,
                        // this could also use cronet?
                        // Instead of using the Envoy proxy driectly,
                        // we use Go code as an Envoy proxy here
                        EnvoyServiceType.HTTP_ECH -> {
                            Log.d(TAG, "OkHttp request to Envoy server")
                            okHttpToEnvoy(chain)
                        }
                        EnvoyServiceType.OKHTTP_PROXY,
                        // all of these services provive a standard SOCKS5
                        // interface. We used to pass these through Cronet,
                        // should we test both? Just use OkHttp for now
                        EnvoyServiceType.OKHTTP_MASQUE,
                        EnvoyServiceType.HYSTERIA2,
                        EnvoyServiceType.V2WS,
                        EnvoyServiceType.V2SRTP,
                        EnvoyServiceType.V2WECHAT,
                        EnvoyServiceType.SHADOWSOCKS, -> {
                            Log.d(TAG, "Passing request to standard proxy")
                            useStandardProxy(chain)
                        }
                        EnvoyServiceType.CRONET_PROXY,
                        EnvoyServiceType.CRONET_MASQUE, -> {
                            Log.d(TAG, "Passing request to cronet w/proxy")
                            useCronet(chain.request(), chain)
                        }
                        else -> {
                            Log.e(TAG, "unsupported activeType: " + it.testType)
                            // pass the request through and hope for the best?
                            chain.proceed(chain.request())
                        }
                    }
                    return res
                }

                Log.e(TAG, "envoyConnected is true, but activeService is not set!")
                // try the request as-is 🤷
                return chain.proceed(chain.request())
            }
        } else {

            if(state.debugTimeoutDriect) {
                Log.w(TAG, "DEBUG - simulating timeout!")
                // I don't know if doing this with runBlocking/delay
                // blocks less than just a sleep here...
                runBlocking {
                    delay(10000)
                    throw SocketTimeoutException("DEBUG timeout connection")
                }
            }

            // let requests pass though and see record if they succeed
            // failures are likely to be timeouts, so don't wait for that
            return observingInterceptor(chain)
        }
    }

    private fun observingInterceptor(chain: Interceptor.Chain): Response {

        // attempt to connect directly (without using EnvoyDirectTest)
        val res = chain.proceed(chain.request())

        if (res.isSuccessful) {
            if (EnvoyNetworking.initialized && EnvoyNetworking.passivelyTestDirect) {
                // XXX is a single 200 enough to say it's working?
                Log.i(TAG, "Direct connections appear to be working, disabling Envoy")
                // XXX direct test instance passed purely to disable any active envoy service
                //   this test should never be run, so actual values shouldn't matter
                state.connectIfNeeded(DirectTransport("direct://", "", 0))
            }
        }

        return res
    }

    // Given an OkHttp Request, return a new one pointed at the Envoy
    // URL with the original request moved in to headers
    private fun getEnvoyRequest(
        req: Request,
        envoyRewrite: Boolean = false): Request
    {
        val builder = req.newBuilder()

        // rewrite the request for an Envoy proxy
        if (envoyRewrite) {
            val t = System.currentTimeMillis()
            val url = req.url
            with (builder) {
                addHeader("Host-Orig", url.host)
                addHeader("Url-Orig", url.toString())
                url(state.activeService!!.url + "?test=" + t)
            }
        }

        // Add any configured (via envoy:// URL) to the request
        state.activeService?.let {
            it.headers.forEach {
                Log.d(TAG, "Adding header $it")
                builder.addHeader(it.first, it.second)
            }
        }

        return builder.build()
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

    // helper to setup the needed Proxy() instance
    private fun setupProxy() {
        state.activeService?.let {
            if (it.proxyUrl.isNullOrEmpty()) {
                Log.e(TAG, "activeService required a proxy, but no proxyUrl is set!?")
                Log.d(TAG, "activeService ${state.activeService}")
                return
            }

            Log.d(TAG, "setting up proxy for URL: ${it.proxyUrl}")
            val uri = Uri.parse(it.proxyUrl)

            val proxyType = when(uri.scheme) {
                "http" -> Proxy.Type.HTTP
                "socks5" -> Proxy.Type.SOCKS
                else -> {
                    Log.e(TAG, "only http and socks are support for OkHttp proxies")
                    return
                }
            }

            var port = uri.port
            if (port == -1) {
                port = when(uri.scheme) {
                    "http"   -> 80
                    "socks5" -> 1080
                    else     -> 80 // ?
                }
            }
            Log.d(TAG, "Creating proxy $proxyType to ${uri.host}:$port")
            proxy = Proxy(proxyType, InetSocketAddress(uri.host, port))
            return
        }
        Log.e(TAG, "can't setup proxy when activeService is null!?")
    }

    private fun useStandardProxy(chain: Interceptor.Chain): Response {
        // I can't find a better way to do this than building a new
        // client with the proxy config
        if (proxy == null) {
            try {
                setupProxy()
            } catch (e: Exception) {
                Log.e(TAG, "Unhandled exception creating proxy: $e")
            }
        }

        val client = OkHttpClient.Builder().proxy(proxy).build()

        Log.d(TAG, "Standard Proxy Request: ${chain.request().url}")

        val req = chain.request().newBuilder().build()
        return client.newCall(req).execute()
    }

    // Use cronet without the Envoy request rewrites. Any socks/http/https
    // proxy setting was applied when the Engine was created
    private fun useCronet(req: Request, chain: Interceptor.Chain): Response {
        state.cronetEngine?.let {
            val callback = CronetUrlRequestCallback(chain.request(), chain.call())
            val urlRequest = CronetNetworking.buildRequest(req, callback, it)
            urlRequest.start()
            return callback.blockForResponse()
        }

        Log.e(TAG, "state.cronetEngine is null!!")
        return chain.proceed(req)
    }

    // Use cronet to make the request to an Envoy proxy. Any socks/http/https
    // proxy setting was applied when the Engine was created
    private fun cronetToEnvoy(chain: Interceptor.Chain): Response {
        val req = getEnvoyRequest(chain.request())

        return useCronet(req, chain)
    }
}
