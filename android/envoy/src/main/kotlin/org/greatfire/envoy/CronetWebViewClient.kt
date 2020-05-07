package org.greatfire.envoy

import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import okhttp3.Headers.Companion.toHeaders
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.chromium.net.CronetEngine
import java.io.IOException

open class CronetWebViewClient(private var mCronetEngine : CronetEngine?) : WebViewClient() {
    companion object {
        private const val TAG = "Envoy"
    }

    constructor() : this(null)

    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
        val interceptor =  if (mCronetEngine == null) CronetInterceptor() else CronetInterceptor(mCronetEngine!!)
        val client: OkHttpClient = OkHttpClient.Builder()
                .addInterceptor(interceptor)
                .build()
        // val client = CronetOkHttpConnectionFactory.client
        val headers = request!!.requestHeaders.toMap().toHeaders()
        val wrappedRequest: Request = Request.Builder().url(request.url.toString()).headers(headers).build()

        Log.i(TAG, "okhttp request sent for url " + request.url.toString() + ", headers: " + headers.toString())
        try {
            val response: Response = client.newCall(wrappedRequest).execute()
            Log.i(TAG, "okhttp headers for " + request.url.toString() + ": " + response.headers)
            val contentType = response.body!!.contentType().toString().split("; ").first()
            val charsetStr = response.body!!.contentType()!!.charset().toString()
            Log.i(TAG, java.lang.String.format("okhttp return for %s: %s %s", request.url, contentType, charsetStr))
            val responseHeaders: MutableMap<String, String> = HashMap()
            for (i in 0 until response.headers.size) {
                responseHeaders[response.headers.name(i)] = response.headers.value(i)
            }
            var message: String = response.message
            if (message.isEmpty()) {
                message = "Unknown error"
            }
            return WebResourceResponse(contentType, charsetStr, response.code, message, responseHeaders,
                    response.body!!.byteStream())
        } catch (e: IOException) {
            Log.e(TAG, "request failed", e)
        }

        // new WebResourceResponse(null, null, 404, "Unknown error", null, null)
        return WebResourceResponse("", "", "".byteInputStream())
    }
}