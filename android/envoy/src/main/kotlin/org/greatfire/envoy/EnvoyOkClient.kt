
package org.greatfire.envoy

import android.net.Uri
import android.util.Log
import health.flo.network.ohttp.client.IsOhttpEnabledProvider
import health.flo.network.ohttp.client.OhttpConfig
import health.flo.network.ohttp.client.setupOhttp
import java.io.File
import java.net.Proxy
import java.util.Collections
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.conscrypt.Conscrypt


class EnvoyOkClient {
    companion object {
        private val TAG = "EnvoyOkClient"

        private fun getClientBuilder(timeout: Long?): OkHttpClient.Builder {
            val builder = OkHttpClient.Builder()

            if (timeout != null) {
                builder.callTimeout(timeout, TimeUnit.SECONDS)
            }

            return builder
        }

        fun getClient(state: EnvoyState, proxy: Proxy? = null, timeout: Long? = null): OkHttpClient {
            val builder = getClientBuilder(timeout)

            if (proxy != null) {
                builder.proxy(proxy)
            }

            return builder.build()
        }

        fun getConcealedAuthClient(state: EnvoyState, timeout: Long? = null): OkHttpClient {
            val builder = getClientBuilder(timeout)

            val tm = Conscrypt.getDefaultX509TrustManager()

            val sslContext: SSLContext = SSLContext.getInstance("SSL").apply {
                init(null, arrayOf<TrustManager>(tm), java.security.SecureRandom())
            }

            builder
                .connectionSpecs(Collections.singletonList(
                    ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                        .tlsVersions(TlsVersion.TLS_1_3).build())
                )
                .sslSocketFactory(sslContext.getSocketFactory(), tm)

            if (state.concealedAuthUser != ""
                && state.concealedAuthPrivateKey != null
                && state.concealedAuthPublicKey != null)
            {
                // We have Concealed Auth config, so add the Interceptor
                val interceptor = HTTPConcealedAuthInterceptor(
                    state.concealedAuthUser!!,
                    state.concealedAuthPublicKey!!,
                    state.concealedAuthPrivateKey!!)

                builder.addNetworkInterceptor(interceptor)
            }

            return builder.build()
        }

        // extracts the OHTTP URL from the active service
        fun getOhttpClient(state: EnvoyState): OkHttpClient {
            val url = state.activeService!!.url
            val tempUri = Uri.parse(url)

            // remove query params and convert to HttpUrl
            // yikes :)
            val relayUrl = tempUri.buildUpon().clearQuery().build().toString()
            Log.d(TAG, "OHTTP URL: $url")

            var keyUrl: String = ""
            tempUri.getQueryParameter("key_url")?.let {
                keyUrl = Uri.decode(it)
                Log.d(TAG, "OHTTP key URL: $keyUrl")
            }

            return getOhttpClient(state, relayUrl, keyUrl)
        }

        fun getOhttpClient(state: EnvoyState, relayUrl: String, keyUrl: String, timeout: Long? = null): OkHttpClient {
            state.ctx?.let {
                // does this benefit from a separate cache?
                // size?
                val configRequestsCache: Cache = Cache(
                    directory = File(it.cacheDir, "ohttp"),
                    maxSize = 50L * 1024L * 1024L // 50 MiB
                )

                // we always use OHTTP for this client
                val isOhttpEnabled: IsOhttpEnabledProvider = IsOhttpEnabledProvider { true }

                val ohttpConfig = OhttpConfig(
                    relayUrl = relayUrl.toHttpUrl(), // relay server
                    userAgent = "GreatFire Envoy/Guardian Project OHTTP", // user agent for OHTTP requests to the relay server
                    configServerConfig = OhttpConfig.ConfigServerConfig(
                        configUrl = keyUrl.toHttpUrl(), // crypto config
                        configCache = configRequestsCache,
                    ),
                )

                val builder = getClientBuilder(timeout)

                return builder.setupOhttp(
                    // setup OHTTP as the final step
                    config = ohttpConfig,
                    isOhttpEnabled = isOhttpEnabled,
                ) // this runs .build()

            }

            Log.e(TAG, "Error setting up OHTTP client")
            // XXX need to return something...
            return OkHttpClient.Builder().build()
        }
    }
}