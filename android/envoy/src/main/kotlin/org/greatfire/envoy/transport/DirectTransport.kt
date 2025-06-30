package org.greatfire.envoy.transport

import org.greatfire.envoy.EnvoyTransportType

import android.content.Context
import android.util.Log
import okhttp3.Request

class DirectTransport(url: String) : Transport(EnvoyTransportType.DIRECT, url) {

    override suspend fun startTest(context: Context): Boolean {
        Log.d(TAG, "Testing direct connection")

        val request = Request.Builder().url(testUrl).head().build()

        return runTest(request, null)
    }
}
