package org.greatfire.envoy.transport

import org.greatfire.envoy.EnvoyTransportType
import org.greatfire.envoy.EnvoyState

import android.content.Context
import android.net.Uri
import android.util.Log
import okhttp3.Request
import java.net.URLEncoder
import java.security.MessageDigest
import kotlin.random.Random


class OkHttpEnvoyTransport(url: String) : Transport(EnvoyTransportType.OKHTTP_ENVOY, url) {

    override suspend fun startTest(context: Context): Boolean {
        val host = Uri.parse(testUrl).host
        if (host == null) {
            Log.e(TAG, "Test URL has no host!?")
            return false
        }

        var salt = Random.Default.nextBytes(16).decodeToString()
        // check for existing salt param
        val tempUri = Uri.parse(url)
        tempUri.getQueryParameter("salt")?.let {
            salt = it
        }

        // add param to create unique url and avoid cached response
        // method based on patched cronet code in url_request_http_job.cc
        val uniqueString = url + salt
        val sha256String = MessageDigest.getInstance("SHA-256").digest(uniqueString.toByteArray()).decodeToString()
        val encodedString = URLEncoder.encode(sha256String, "UTF-8")
        val tempUrl = url + "?digest=" + encodedString
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
