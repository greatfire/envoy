package org.greatfire.envoy.transport

import org.greatfire.envoy.EnvoyTransportType
import org.greatfire.envoy.EnvoyState

import android.content.Context
import android.net.Uri
import android.util.Log
import okhttp3.Request
import java.net.URLEncoder
import java.security.MessageDigest


class OkHttpEnvoyTransport(url: String) : Transport(EnvoyTransportType.OKHTTP_ENVOY, url) {

    companion object {
        fun envoyProxyRewrite(
            builder: Request.Builder,
            envoyUrl: String,
            targetUrl: String,
            urlSalt: String) : Request.Builder
        {
            var host = Uri.parse(targetUrl).host
            if (host == null) {
                // this really shouldn't be possible
                Log.e(TAG, "targetUrl has an empty host somehow")
                host = "www.example.com"
            }
            // add param to create unique url and avoid cached response
            // method based on patched cronet code in url_request_http_job.cc
            val uniqueString = targetUrl + urlSalt
            val sha256String = MessageDigest.getInstance("SHA-256")
                .digest(uniqueString.toByteArray())
                .decodeToString()
            val encodedString = URLEncoder.encode(sha256String, "UTF-8")
            val tempUrl = "${envoyUrl}?digest=${encodedString}"
            return builder
                .url(tempUrl)
                .addHeader("Url-Orig", targetUrl)
                .addHeader("Host-Orig", host)
        }
    }

    override suspend fun startTest(context: Context): Boolean {
        val request = envoyProxyRewrite(
            Request.Builder(), url, testUrl, salt).build()

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
