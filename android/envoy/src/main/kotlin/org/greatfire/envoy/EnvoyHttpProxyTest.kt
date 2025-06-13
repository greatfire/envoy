package org.greatfire.envoy

import android.content.Context
import android.net.Uri
import android.util.Log
import okhttp3.Request

class EnvoyHttpProxyTest(envoyUrl: String, testUrl: String, testResponseCode: Int) : EnvoyTest(EnvoyServiceType.OKHTTP_PROXY, envoyUrl, testUrl, testResponseCode) {
    companion object {
        private const val TAG = "EnvoyHttpProxyTest"
    }

    override suspend fun startTest(context: Context): Boolean {
        /*
        val proxyUri = Uri.parse(testUrl)
        if (proxyUri.getScheme() == "envoy") {
            // XXX handle envoy:// URLs
            Log.e(TAG, "envoy:// URLs aren't supported yet ☹️")
            return false
        } else {
            // assume this is an http(s) Evnoy proxy
            val host = Uri.parse(EnvoyConnectionTests.testUrl).host
            if (host == null) {
                // this shouldn't happen
                return false
            }
            // XXX cache param, this is hacky :)
            val t = System.currentTimeMillis()
            val url = proxyUrl.toString() + "?test=" + t
            val request = Request.Builder().url(url).head()
                .addHeader("Url-Orig", EnvoyConnectionTests.testUrl)
                .addHeader("Host-Orig", host)
                .build()

            return runTest(request, null)
        }
        */

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