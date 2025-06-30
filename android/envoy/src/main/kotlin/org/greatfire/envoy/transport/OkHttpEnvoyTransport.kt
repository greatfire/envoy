package org.greatfire.envoy.transport

import org.greatfire.envoy.EnvoyServiceType

import android.content.Context
import android.net.Uri
import android.util.Log
import okhttp3.Request

class OkHttpEnvoyTransport(url: String) : Transport(EnvoyServiceType.OKHTTP_ENVOY, url) {

    override suspend fun startTest(context: Context): Boolean {
        val host = Uri.parse(testUrl).host
        if (host == null) {
            Log.e(TAG, "Test URL has no host!?")
            return false
        }

        // XXX cache param, this is hacky :)
        // this should be updated to use the same checksum param
        // that the C++ patches used to use
        val t = System.currentTimeMillis()
        val tempUrl = url + "?test=" + t
        val request = Request.Builder()
            .url(tempUrl)
            // .head()  // a HEAD request is enough to test it works
            .addHeader("Url-Orig", testUrl)
            .addHeader("Host-Orig", host)
            .build()

        val temp = runTest(request, null)
        if (temp == true && this.proxyUrl.isNullOrEmpty()) {
            // the Envoy proxy URL needs to be copied to proxyUrl
            this.proxyUrl = url
        }
        return temp
    }

    override fun stopService() {
        // no service to stop
    }
}
