package org.greatfire.envoy.transport

import org.greatfire.envoy.EnvoyTransportType
import org.greatfire.envoy.EnvoyState

import android.content.Context
import android.net.Uri
import android.util.Log
import javax.net.ssl.SSLSocket
import okhttp3.*
import org.conscrypt.Conscrypt


class OkHttpEnvoyTransport(url: String) : Transport(EnvoyTransportType.OKHTTP_ENVOY, url) {

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
        val requestBuilder = Request.Builder()
            .url(tempUrl)
            // .head()  // a HEAD request is enough to test it works
            .addHeader("Url-Orig", testUrl)
            .addHeader("Host-Orig", host)

        val request = requestBuilder.build()

        val temp = runTest(request)
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
