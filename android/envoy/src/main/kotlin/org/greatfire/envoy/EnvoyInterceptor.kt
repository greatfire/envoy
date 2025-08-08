package org.greatfire.envoy

import org.greatfire.envoy.transport.DirectTransport
import org.greatfire.envoy.transport.Transport

import android.net.Uri
import android.util.Log
import health.flo.network.ohttp.client.IsOhttpEnabledProvider
import health.flo.network.ohttp.client.OhttpConfig
import health.flo.network.ohttp.client.setupOhttp
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File
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

    private var OhttpClient: OkHttpClient? = null

    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {

        val req = chain.request()

        if (state.connected.get()) {
            if (state.activeServiceType.get() == EnvoyTransportType.DIRECT.ordinal) {
                Log.d(TAG, "Direct: " + req.url)
                // pass the request through
                return chain.proceed(chain.request())
            } else {
                state.activeService?.let {
                    // proxy via Envoy
                    Log.d(TAG, "Proxy Via Envoy: " + it.testType)
                    val res = when (it.testType) {
                        EnvoyTransportType.CRONET_ENVOY -> {
                            Log.d(TAG, "Cronet request to Envoy server")
                            cronetToEnvoy(chain)
                        }
                        EnvoyTransportType.OKHTTP_ENVOY,
                        // this could also use cronet?
                        // Instead of using the Envoy proxy driectly,
                        // we use Go code as an Envoy proxy here
                        EnvoyTransportType.HTTP_ECH -> {
                            Log.d(TAG, "OkHttp request to Envoy server")
                            okHttpToEnvoy(chain)
                        }
                        EnvoyTransportType.OKHTTP_PROXY,
                        // all of these services provive a standard SOCKS5
                        // interface. We used to pass these through Cronet,
                        // should we test both? Just use OkHttp for now
                        EnvoyTransportType.OKHTTP_MASQUE,
                        EnvoyTransportType.HYSTERIA2,
                        EnvoyTransportType.V2WS,
                        EnvoyTransportType.V2SRTP,
                        EnvoyTransportType.V2WECHAT,
                        EnvoyTransportType.SHADOWSOCKS, -> {
                            Log.d(TAG, "Passing request to standard proxy")
                            useStandardProxy(chain)
                        }
                        EnvoyTransportType.CRONET_PROXY,
                        EnvoyTransportType.CRONET_MASQUE, -> {
                            Log.d(TAG, "Passing request to cronet w/proxy")
                            useCronet(chain.request(), chain)
                        }
                        EnvoyTransportType.OHTTP -> {
                            Log.d(TAG, "Passing request to OHTTP")
                            useOhttp(chain.request(), chain)
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
                state.connectIfNeeded(DirectTransport("direct://"))
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
            if (!state.activeService!!.proxyUrl.isNullOrEmpty()) {
                Log.d(TAG, "Using Envoy proxy ${state.activeService!!.proxyUrl} for url ${req.url}")
                val t = System.currentTimeMillis()
                val url = req.url
                with (builder) {
                    addHeader("Host-Orig", url.host)
                    addHeader("Url-Orig", url.toString())
                    url(state.activeService!!.proxyUrl + "?test=" + t)
                }
            } else {
                Log.e(TAG, "INTERNAL ERROR, and Envoy proxy is selected but proxyUrl is empty")
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

        return chain.proceed(getEnvoyRequest(origRequest, true))
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

    // Use cronet to make the reuqest unmodified (presumably it's configured
    // with a proxy)
    private fun cronetToProxy(chain: Interceptor.Chain): Response {
        val callback = CronetUrlRequestCallback(chain.request(), chain.call())
        val urlRequest = CronetNetworking.buildRequest(
            chain.request(), callback, state.cronetEngine!!
        )
        urlRequest.start()
        return callback.blockForResponse()
    }

    private fun setupOhttp() {
        state.ctx?.let {

            // does this benefit from a separate cache?
            // size?
            val configRequestsCache: Cache = Cache(
                directory = File(it.cacheDir, "ohttp"),
                maxSize = 50L * 1024L * 1024L // 50 MiB
            )

            // we always use OHTTP for this client
            val isOhttpEnabled: IsOhttpEnabledProvider = IsOhttpEnabledProvider { true }

            val url = state.activeService!!.url
            val tempUri = Uri.parse(url)

            // remove query params and convert to HttpUrl
            // yikes :)
            val relayUrl = tempUri.buildUpon().clearQuery().build().toString().toHttpUrl()
            Log.d(TAG, "OHTTP URL: $url")

            tempUri.getQueryParameter("key_url")?.let {
                val keyUrl = Uri.decode(it).toHttpUrl()

                Log.d(TAG, "OHTTP key URL: $keyUrl")

                val ohttpConfig = OhttpConfig(
                    relayUrl = relayUrl, // relay server
                    userAgent = "GreatFire Envoy/Guardian Project OHTTP", // user agent for OHTTP requests to the relay server
                    configServerConfig = OhttpConfig.ConfigServerConfig(
                        configUrl = keyUrl, // crypto config
                        configCache = configRequestsCache,
                    ),
                )

                OhttpClient = OkHttpClient.Builder()
                    .setupOhttp( // setup OHTTP as the final step
                       config=ohttpConfig,
                       isOhttpEnabled = isOhttpEnabled,
                    )
            }

            return
        }

        Log.e(TAG, "no context for OHTTP")
    }

    // Use OHTTP to service the request
    private fun useOhttp(req: Request, chain: Interceptor.Chain): Response {
        // the OHTTP code has it's own interceptors, one of each, and special
        // helper to apply them. Creating another client here for OHTTP is a
        // a little gross, but it makes it cleaner to integrate Envoy in to
        // the app (e.g. they only need to apply this Interceptor and not
        // the OHTTP ones)
        if (OhttpClient == null) {
            setupOhttp()
        }

        if (OhttpClient != null) {

            Log.d(TAG, "🗿Using OHTTP")

            val req = chain.request().newBuilder()
                // our OHTTP gateway doesn't support gzip encoding, this works
                // around that. TODO: remove this when it's not needed
                .removeHeader("Accept-Encoding").addHeader("Accept-Encoding", "identity")
                .build()
            return OhttpClient!!.newCall(req).execute()
        } else {
            Log.e(TAG, "OhttpClient is undefined!")
            return chain.proceed(req)
        }
    }
}
