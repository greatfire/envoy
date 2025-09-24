package org.greatfire.envoy.transport

import org.greatfire.envoy.EnvoyTransportType
import org.greatfire.envoy.EnvoyOkClient

import android.content.Context
import android.net.Uri
import android.util.Log
import okhttp3.Request


class OhttpTransport(url: String) : Transport(EnvoyTransportType.OHTTP, url) {

    override suspend fun startTest(context: Context): Boolean {

        val tempUri = Uri.parse(url)

        val param = tempUri.getQueryParameter("key_url")

        if (param.isNullOrEmpty()) {
            Log.w(TAG, "OHTTP key URL is required")
            return false
        }

        val keyUrl = Uri.decode(param)

        // remove query params
        val relayUrl = tempUri.buildUpon().clearQuery().build().toString()

        val client = EnvoyOkClient.getOhttpClient(state, relayUrl, keyUrl, 20)

        val request = Request.Builder().url(testUrl).build()

        return runTest(request, null, client)
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