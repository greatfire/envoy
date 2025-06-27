package org.greatfire.envoy

import android.content.Context
import android.util.Log
import okhttp3.Request

class DirectTransport(envoyUrl: String, testUrl: String, testResponseCode: Int) : Transport(EnvoyServiceType.DIRECT, envoyUrl, testUrl, testResponseCode) {

    override suspend fun startTest(context: Context): Boolean {
        Log.d(TAG, "Testing direct connection")

        val request = Request.Builder().url(testUrl).head().build()

        return runTest(request, null)
    }
}