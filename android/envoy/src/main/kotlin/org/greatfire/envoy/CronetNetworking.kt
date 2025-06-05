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
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors


object CronetNetworking {
    private var mCronetEngine: CronetEngine? = null
    private val mExecutorService = Executors.newSingleThreadExecutor()
    // private var mCustomCronetBuilder: CustomCronetBuilder? = null

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
        // XXX TLS options here, if we support them

        return builder.build()
    }

    @JvmStatic
    fun cronetEngine(): CronetEngine? {
        return mCronetEngine
    }

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
}
