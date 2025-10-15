package org.greatfire.envoy

import android.util.Log
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

//
// This interceptor prevents redirects from downgrading HTTPS connections
// to HTTP
//
// OkHttp's followSslRedirects prevents redirects from HTTP -> HTTPS as well.
// Following those seems desirable

class HTTPSDowngradePreventionInterceptor : Interceptor {

    companion object {
        private const val TAG = "Envoy-HTTPSDowngradePreventionInterceptor"
    }

    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        val response = chain.proceed(request)

        if (request.isHttps && response.isRedirect) {
            if (response.request.url.scheme == "https") {
                return response
            } else {
                Log.e(TAG, "Preventing redirect from HTTPS to HTTP")
                throw IOException("Preventing redirect from HTTPS to HTTP")
            }
        }

        // either we're not making an HTTPS request or, more likely
        // the response isn't a redirect
        return chain.proceed(request)
    }
}
