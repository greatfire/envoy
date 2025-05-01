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
import java.net.URI
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

    fun buildEngine(
        context: Context,
        cacheFolder: String? = null,
        envoyUrl: String? = null,
        proxyUrl: String? = null,
        resolverRules: String? = null,
        cacheSize: Long = 0,
        strategy: Int = 0
    ): CronetEngine {
        var builder = CronetEngine.Builder(context)
            .enableBrotli(true)
            .enableHttp2(true)
            .enableQuic(true)

        if (!cacheFolder.isNullOrEmpty() && cacheSize > 0) {
            val cacheDir = File(context.cacheDir, cacheFolder)
            if (!cacheDir.exists()) {
                cacheDir.mkdirs()
            }
            builder = builder
                .setStoragePath(cacheDir.absolutePath)
                .enableHttpCache(CronetEngine.Builder.HTTP_CACHE_DISK, cacheSize * 1024 * 1024)
        }

        envoyUrl?.let {
            Log.e(TAG, "envoyUrl is unsupported here now!")
            // builder = builder.setEnvoyUrl(it)
        }

        proxyUrl?.let {
            builder = builder.setProxyUrl(it)
        }
        resolverRules?.let {
            builder = builder.setResolverRules(it)
        }

        // if (strategy > 0) {
        //     builder = builder.SetStrategy(strategy)
        // }
        return builder.build()
    }

    @JvmStatic
    fun cronetEngine(): CronetEngine? {
        return mCronetEngine
    }

    //@JvmStatic
    //fun setCustomCronetBuilder(builder: CustomCronetBuilder?) {
    //    customCronetBuilder = builder
    //}

    // @JvmStatic
    // @Synchronized
    // @JvmOverloads
    // fun initializeCronetEngine(context: Context, envoyUrl: String?, reInitializeIfNeeded: Boolean = false, strategy: Int = 0) {
    //     Log.d(TAG, "try to initialize cronet engine with url $envoyUrl")
    //     if (this.mCronetEngine != null && !reInitializeIfNeeded) {
    //         Log.d(TAG, "cronet engine is initialized already, and reInitializeIfNeeded is $reInitializeIfNeeded")
    //         return
    //     }
    //     if (mCustomCronetBuilder != null) {
    //         mCronetEngine = mCustomCronetBuilder!!.build(context)
    //     } else {
    //         mCronetEngine = buildEngine(
    //             context = context,
    //             cacheFolder = "cronet-cache",
    //             envoyUrl = envoyUrl,
    //             strategy = strategy,
    //             cacheSize = 10
    //         )
    //         if (mCronetEngine != null) {
    //             Log.d(TAG, "engine version " + mCronetEngine!!.versionString)
    //             val factory = mCronetEngine!!.createURLStreamHandlerFactory()
    //             // https://stackoverflow.com/questions/30267447/seturlstreamhandlerfactory-and-java-lang-error-factory-already-set
    //             try {
    //                 // Try doing it the normal way
    //                 URL.setURLStreamHandlerFactory(factory)
    //             } catch (e: Error) {
    //                 // Force it via reflection
    //                 try {
    //                     val factoryField: Field = URL::class.java.getDeclaredField("factory")
    //                     factoryField.isAccessible = true
    //                     factoryField.set(null, factory)
    //                 } catch (ex: Exception) {
    //                     when (ex) {
    //                         is NoSuchFieldException, is IllegalAccessException ->
    //                             Log.e(TAG, "Could not access factory field on URL class: {}", e)
    //                         else -> throw ex
    //                     }
    //                 }
    //             }
    //         } else {
    //             Log.e(TAG, "failed to initialize cronet engine")
    //         }
    //     }
    // }

    @JvmStatic
    @Throws(IOException::class)
    fun buildRequest(request: Request, callback: UrlRequest.Callback?, cronetEngine: CronetEngine, executorService: ExecutorService): UrlRequest {
        val url = request.url.toString()
        val requestBuilder = cronetEngine.newUrlRequestBuilder(url, callback, executorService)
        requestBuilder.setHttpMethod(request.method)
        request.headers.forEach {
            if (it.first.lowercase(Locale.ENGLISH) != "accept-encoding") {
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

    /*
        Like buildRequest, but assumes an unpatched Cronet
    */
    @JvmStatic
    @Throws(IOException::class)
    fun buildEnvoyRequest(
        envoyUrl: String,
        request: Request,
        callback: UrlRequest.Callback?,
        cronetEngine: CronetEngine,
        executorService: ExecutorService
    ) : UrlRequest {
        val targetUrl = request.url.toString()
        val targetHost = URI(targetUrl).getHost()

        val requestBuilder = cronetEngine.newUrlRequestBuilder(envoyUrl, callback, executorService)
        requestBuilder.setHttpMethod(request.method)
        request.headers.forEach {
            if (it.first.lowercase(Locale.ENGLISH) != "accept-encoding") {
                requestBuilder.addHeader(it.first, it.second)
            }
        }
        // set Envoy headers
        requestBuilder.addHeader("Host-Orig", targetHost)
        requestBuilder.addHeader("Url-Orig", targetUrl)

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
