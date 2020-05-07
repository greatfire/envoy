package org.greatfire.envoy

import android.content.Context
import android.util.Log
import okhttp3.Request
import okio.Buffer
import org.chromium.net.CronetEngine
import org.chromium.net.UploadDataProviders
import org.chromium.net.UrlRequest
import java.io.File
import java.io.IOException
import java.net.URL
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/*
import java.security.Provider
import java.security.Security
 */

object CronetNetworking {
    private var mCronetEngine: CronetEngine? = null
    private val mExecutorService = Executors.newSingleThreadExecutor()
    private var mCustomCronetBuilder: CustomCronetBuilder? = null

    private const val TAG = "Envoy"

    @JvmStatic
    fun cronetEngine(): CronetEngine? {
        return mCronetEngine
    }

    //@JvmStatic
    //fun setCustomCronetBuilder(builder: CustomCronetBuilder?) {
    //    customCronetBuilder = builder
    //}

    @JvmStatic
    @Synchronized
    fun initializeCronetEngine(context: Context, envoyUrl: String?) {
        if (this.mCronetEngine != null) {
            // TODO re-init
            return
        }
        if (mCustomCronetBuilder != null) {
            mCronetEngine = mCustomCronetBuilder!!.build(context)
        } else {
            val cacheDir = File(context.cacheDir, "cronet-cache")
            cacheDir.mkdirs()
            Log.d(TAG, "try to build cronet engine")
            mCronetEngine = CronetEngine.Builder(context)
                    // .setUserAgent("curl/7.66.0")
                    .enableBrotli(true)
                    .enableHttp2(true)
                    .enableQuic(true)
                    .setEnvoyUrl(envoyUrl)
                    .setStoragePath(cacheDir.absolutePath)
                    .enableHttpCache(CronetEngine.Builder.HTTP_CACHE_DISK, 10 * 1024 * 1024) // 10 MegaBytes
                    .build()
            if (mCronetEngine != null) {
                Log.d(TAG, "engine version " + mCronetEngine!!.versionString)
                URL.setURLStreamHandlerFactory(mCronetEngine!!.createURLStreamHandlerFactory())
            } else {
                Log.e(TAG, "failed to build cronet engine")
            }
        }
    }


    @JvmStatic
    @Throws(IOException::class)
    fun buildRequest(request: Request, callback: UrlRequest.Callback?, cronetEngine: CronetEngine, executorService: ExecutorService): UrlRequest {
        val url = request.url.toString()
        val requestBuilder = cronetEngine.newUrlRequestBuilder(url, callback, executorService)
        requestBuilder.setHttpMethod(request.method)
        request.headers.forEach {
            if (it.first.toLowerCase(Locale.ENGLISH) != "accept-encoding") {
               // Log.d(TAG, "add header for url $url: ${it.first}, ${it.second}")
               requestBuilder.addHeader(it.first, it.second)
           }
        }

        val requestBody = request.body
        if (requestBody != null) {
            val contentType = requestBody.contentType()
            if (contentType != null) {
                requestBuilder.addHeader("Content-Type", contentType.toString())
            }
            val buffer = Buffer()
            requestBody.writeTo(buffer)
            val uploadDataProvider = UploadDataProviders.create(buffer.readByteArray())
            requestBuilder.setUploadDataProvider(uploadDataProvider, executorService)
        }

        return requestBuilder.build()
    }

    @JvmStatic
    @Throws(IOException::class)
    fun buildRequest(request: Request, callback: UrlRequest.Callback?, cronetEngine: CronetEngine): UrlRequest {
        return buildRequest(request, callback, cronetEngine, mExecutorService)
    }

    @JvmStatic
    @Throws(IOException::class)
    fun buildRequest(request: Request, callback: UrlRequest.Callback?): UrlRequest {
        return buildRequest(request, callback, mCronetEngine!!, mExecutorService)
    }

    interface CustomCronetBuilder {
        fun build(context: Context?): CronetEngine?
    }

}
