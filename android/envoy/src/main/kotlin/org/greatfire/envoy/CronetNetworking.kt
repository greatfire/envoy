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
import java.lang.reflect.Field
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
    @JvmOverloads
    fun initializeCronetEngine(context: Context, envoyUrl: String?, reInitializeIfNeeded: Boolean = false) {
        Log.d(TAG, "try to build cronet engine with $envoyUrl")
        if (this.mCronetEngine != null && !reInitializeIfNeeded) {
            Log.d(TAG, "cronet engine is initialized already, and reInitializeIfNeeded is $reInitializeIfNeeded")
            return
        }
        if (mCustomCronetBuilder != null) {
            mCronetEngine = mCustomCronetBuilder!!.build(context)
        } else {
            val cacheDir = File(context.cacheDir, "cronet-cache")
            cacheDir.mkdirs()
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
                val factory = mCronetEngine!!.createURLStreamHandlerFactory()
                // https://stackoverflow.com/questions/30267447/seturlstreamhandlerfactory-and-java-lang-error-factory-already-set
                try {
                    // Try doing it the normal way
                    URL.setURLStreamHandlerFactory(factory)
                } catch (e: Error) {
                    // Force it via reflection
                    try {
                        val factoryField: Field = URL::class.java.getDeclaredField("factory")
                        factoryField.isAccessible = true
                        factoryField.set(null, factory)
                    } catch (ex: Exception) {
                        when (ex) {
                            is NoSuchFieldException, is IllegalAccessException ->
                                Log.e(TAG, "Could not access factory field on URL class: {}", e)
                            else -> throw ex
                        }
                    }
                }
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
