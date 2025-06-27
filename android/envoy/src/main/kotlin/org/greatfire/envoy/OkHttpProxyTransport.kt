package org.greatfire.envoy

import android.content.Context
import android.net.Uri
import android.util.Log
import okhttp3.Request

class OkHttpProxyTransport(url: String) : Transport(EnvoyServiceType.OKHTTP_PROXY, url) {

    override suspend fun startTest(context: Context): Boolean {
        val host = Uri.parse(testUrl).host
        if (host == null) {
            Log.e(TAG, "Test URL has no host!?")
            return false
        }

        // XXX cache param, this is hacky :)
        // this nshould be updated to use the same checksum param
        // that the C++ patches used to use
        val t = System.currentTimeMillis()
        val url = proxyUrl.toString() + "?test=" + t
        val request = Request.Builder()
            .url(url)
            // .head()  // a HEAD request is enough to test it works
            .addHeader("Url-Orig", testUrl)
            .addHeader("Host-Orig", host)
            .build()

        return runTest(request, null)
    }
}