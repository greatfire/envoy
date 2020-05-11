package org.greatfire.envoy

import android.util.Log
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import org.chromium.net.CronetEngine
import java.io.IOException

class CronetInterceptor(private var mCronetEngine: CronetEngine?) : Interceptor {

    companion object {
        private const val TAG = "Envoy"
    }

    constructor() : this(null)

    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        return when {
            mCronetEngine != null -> {
                Log.d(TAG, "hit interceptor for " + chain.request().url)
                proxyToCronet(chain.request(), chain.call(), mCronetEngine!!)
            }
            CronetNetworking.cronetEngine() != null -> {
                Log.d(TAG, "hit global interceptor for " + chain.request().url)
                // This will stop later interceptors
                proxyToCronet(chain.request(), chain.call())
            }
            else -> {
                Log.d(TAG, "bypass interceptor for " + chain.request().url)
                chain.proceed(chain.request())
            }
        }
    }

    @Throws(IOException::class)
    private fun proxyToCronet(request: Request, call: Call): Response {
        // eventListener=eventListener, responseCallback=responseCallback
        val callback = CronetUrlRequestCallback(request, call)
        val urlRequest = CronetNetworking.buildRequest(request, callback)
        urlRequest.start()
        return callback.blockForResponse()
    }

    @Throws(IOException::class)
    private fun proxyToCronet(request: Request, call: Call, cronetEngine: CronetEngine): Response {
        val callback = CronetUrlRequestCallback(request, call)
        val urlRequest = CronetNetworking.buildRequest(request, callback, cronetEngine)
        urlRequest.start()
        return callback.blockForResponse()
    }
}
