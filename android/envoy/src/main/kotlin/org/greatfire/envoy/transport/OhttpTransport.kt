package org.greatfire.envoy.transport

import org.greatfire.envoy.EnvoyTransportType

import android.content.Context
import android.net.Uri
import android.util.Log
import health.flo.network.ohttp.client.IsOhttpEnabledProvider
import health.flo.network.ohttp.client.OhttpConfig
import health.flo.network.ohttp.client.setupOhttp
import java.io.File
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import okhttp3.*
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl

class OhttpTransport(url: String) : Transport(EnvoyTransportType.OHTTP, url) {

    override suspend fun startTest(context: Context): Boolean {

        val tempUri = Uri.parse(url)

        val param = tempUri.getQueryParameter("key_url")

        if (param.isNullOrEmpty()) {
            Log.w(TAG, "OHTTP key URL is required")
            return false
        }

        val keyUrl = Uri.decode(param).toHttpUrl()

        // remove query params and convert to HttpUrl
        val relayUrl = tempUri.buildUpon().clearQuery().build().toString().toHttpUrl()

        // this is required, make it small?
        val configRequestsCache: Cache = Cache(
            directory = File(state.ctx!!.cacheDir, "ohttp"),
            maxSize = 5L * 1024L * 1024L // 5 MiB
        )

        // we always use OHTTP for this client
        val isOhttpEnabled: IsOhttpEnabledProvider = IsOhttpEnabledProvider { true }

        val ohttpConfig = OhttpConfig(
            relayUrl = relayUrl, // relay server
            userAgent = "GreatFire Envoy/Guardian Project OHTTP", // user agent for OHTTP requests to the relay server
            configServerConfig = OhttpConfig.ConfigServerConfig(
                configUrl = keyUrl, // crypto config
                configCache = configRequestsCache,
            ),
        )

        // this is a lot like runTest, should we just pass the client
        // in to that?
        val client = OkHttpClient.Builder()
            // short timeout for testing
            .callTimeout(20, TimeUnit.SECONDS)
            .setupOhttp( // setup OHTTP as the final step
               config=ohttpConfig,
               isOhttpEnabled = isOhttpEnabled,
            ) // this runs .build()

        val request = Request.Builder().url(testUrl).build()

        Log.d(TAG, "Testing OHTTP request to: ${request.url}")

        try {
            val response = client.newCall(request).execute()
            val code = response.code
            Log.d(TAG, "request: " + request + ", got code: " + code)
            return (code == testResponseCode)
        } catch (e: InterruptedIOException) {
            Log.e(TAG, "Test timed out for request" + request)
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Test threw an error for request" + request)
            Log.e(TAG, "error: " + e)
            return false
        }
    }

    override suspend fun startService(): String {
        // No service
        return ""
    }

    override fun stopService() {
        // No service
        return
    }

}