package org.greatfire.envoy.transport

import org.greatfire.envoy.EnvoyServiceType

import android.content.Context
import android.util.Log
import okhttp3.Request

class DirectTransport(url: String) : Transport(EnvoyServiceType.DIRECT, url) {

    override suspend fun startTest(context: Context): Boolean {
        Log.d(TAG, "Testing direct connection")

        val request = Request.Builder().url(testUrl).head().build()

        return runTest(request, null)
    }
}
