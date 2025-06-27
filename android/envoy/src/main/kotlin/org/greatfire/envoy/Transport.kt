package org.greatfire.envoy

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ConditionVariable
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.chromium.net.CronetException
import org.chromium.net.UrlRequest
import org.chromium.net.UrlResponseInfo
import java.io.IOException
import java.io.InterruptedIOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// This class represents an Envoy connection: type and URL,
// and tracks additional information, such as a URL to any
// proxy used, e.g. Shadowsocks provides a SOCKS5 interface
// which is what our Interceptor actually uses
//
// The url should be the one provided by the caller
// testType lets us know how to treat that URL
// some transports have an additional URL, e.g. a SOCKS5 URL
// for a PT, that gets stored in proxyUrl

open class Transport(
    var testType: EnvoyServiceType = EnvoyServiceType.UNKNOWN,
    var url: String
) {
    companion object {
        const val TAG = "Envoy - Transport"

        // these are set by helpers in EnvoyNetworking's companion object
        // Target URL/response code for testing
        var testUrl = "https://www.google.com/generate_204"
        // using a 200 code makes it really easy to get false positives
        var testResponseCode = 204
        // direct URL to the site for testing
        var directUrl = ""
    }

    // proxy URL for the service providing transport
    // can be SOCKS5, HTTP(S), or Envoy
    var proxyUrl: String? = null

    // Is this the service we chose to use
    var selectedService: Boolean = false

    // Is the associated service (if any) running?
    var serviceRunning: Boolean = false

    // envoy:// URL related options
    val headers = mutableListOf<Pair<String, String>>()
    // address param creates an resolver rule
    // stash it here in case we can support it with OkHttp
    var address: String? = null
    var resolverRules: String? = null

    // Envoy Global settings and state
    protected val state = EnvoyState.getInstance()

    // used to time how long it takes to connect and test
    protected var timer: Timer? = null
    // should this be in settings?
    protected val shadowsocksIntent: Intent? = null

    override fun toString(): String {
        return UrlUtil.sanitizeUrl(url) + " (" + testType + ")"
    }

    // helper to time things
    inner class Timer() {
        private val startTime = System.currentTimeMillis()
        private var stopTime: Long? = null

        fun stop(): Long {
            stopTime = System.currentTimeMillis()
            return stopTime!! - startTime
        }

        fun timeSpent(): Long {
            if (stopTime == null) {
                Log.e(TAG, "timeSpent called before stop()!")
                return 0
            }
            return stopTime!! - startTime
        }
    }

    open suspend fun startService(): String {
        Log.e(TAG, "Tried to start an unknown service")
        return ""
    }

    open fun stopService() {
        Log.e(TAG, "Tried to stop an unknown service")
        Log.e(TAG, Log.getStackTraceString(Throwable()))
    }

    open suspend fun startTest(context: Context): Boolean {
        Log.e(TAG, "Tried to test an unknown service")
        Log.e(TAG, Log.getStackTraceString(Throwable()))
        return false
    }

    // helper, given a request and optional proxy, test the connection
    protected fun runTest(request: Request, proxy: java.net.Proxy?): Boolean {
        val builder = OkHttpClient.Builder();
        if (proxy != null) {
            builder.proxy(proxy)
        }

        val client = builder.callTimeout(20, TimeUnit.SECONDS).build()

        Log.d(TAG, "testing request to: ${request.url} with proxy $proxy")

        try {
            val response = client.newCall(request).execute()
            val code = response.code
            Log.d(TAG, "request: " + request + ", got code: " + code)
            return(code == testResponseCode)
        } catch (e: InterruptedIOException) {
            Log.e(TAG, "Test timed out for request" + request)
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Test threw an error for request" + request)
            Log.e(TAG, "error: " + e)
            return false
        }
    }

    inner class TestUrlRequestCallback() : UrlRequest.Callback()
    {
        var succeeded = false
        private val requestDone = ConditionVariable()

        override fun onRedirectReceived(
            request: UrlRequest?,
            info: UrlResponseInfo?,
            newLocationUrl: String?
        ) {
            // we shouldn't get these in testing, but follow it anyway
            request?.followRedirect()
        }

        override fun onResponseStarted(
            request: UrlRequest?,
            info: UrlResponseInfo
        ) {
            // is this the best thing to do with the ignored data?
            request?.read(ByteBuffer.allocateDirect(102400))
        }

        override fun onReadCompleted(
            request: UrlRequest?,
            info: UrlResponseInfo?,
            byteBuffer: ByteBuffer?,
        ) {
            request?.read(byteBuffer)
        }

        override fun onSucceeded(
            request: UrlRequest?,
            info: UrlResponseInfo?
        ) {
            if (info?.httpStatusCode == testResponseCode) {
                Log.d(TAG, "Cronet worked!")
                succeeded = true
            } else {
                Log.e(TAG, "Cronet failed")
            }
            requestDone.open()
        }

        override fun onFailed(
            request: UrlRequest?,
            info: UrlResponseInfo?,
            error: CronetException?,
        ) {
            Log.e(TAG, "Cronet failed: " + error)
            requestDone.open()
        }

        override fun onCanceled(
            request: UrlRequest?,
            info: UrlResponseInfo?,
        ) {
            Log.d(TAG, "Cronet canceled.")
            requestDone.open()
        }

        // Wait until cronet is done
        @Throws(IOException::class)
        fun blockForResponse() {
            requestDone.block()
        }
    }

    // Helper to test a request to a standard SOCKS or HTTP proxy using OkHttp
    suspend fun testStandardProxy(proxyUri: Uri, testResponseCode: Int): Boolean {
        Log.d(TAG, "Testing standard proxy $proxyUri")

        if (proxyUri.host.isNullOrEmpty()) {
            Log.e(TAG, "Empty proxy host!?")
            return false
        }

        // only socks5 and http are supported here
        var proxyType = when(proxyUri.scheme) {
            "socks5" -> Proxy.Type.SOCKS
            "http" -> Proxy.Type.HTTP
            else -> {
                Log.e(TAG, "Unsupported proxy scheme")
                return false
            }
        }

        // apparently knowing the default port for the protocol is too
        // much for the Uri libaray
        var port = proxyUri.port
        if (port == -1) {
            port = when(proxyUri.scheme) {
                "http"   -> 80
                "socks5" -> 1080
                else  -> 80 // ? error?
            }
        }

        Log.d(TAG, "🦐🦐 proxy ${proxyUri.host}:$port")

        proxyUri.host?.let {
            val proxy = getProxy(proxyType, it, port)
            val request = Request.Builder().url(testUrl).build()

            return runTest(request, proxy)
        }
        // proxyUri.host is null somehow
        return false
    }

    // this is currently unused
    // suspend fun testCronetProxy(testResponseCode: Int, context: Context): Boolean {
    //     var proxyUrl = url

    //     val cronetEngine = CronetNetworking.buildEngine(
    //         context = context,
    //         cacheFolder = null, // no cache XXX?
    //         proxyUrl = proxyUrl,
    //         resolverRules = resolverRules,
    //         cacheSize = 0, // what are the units here?
    //     )
    //     val callback = TestUrlRequestCallback()
    //     // We're just making a standard request via Cronet to a standard proxy
    //     val requestBuilder = cronetEngine.newUrlRequestBuilder(
    //         testUrl,
    //         callback,
    //         Executors.newCachedThreadPool()
    //     )

    //     val request = requestBuilder.build()
    //     request.start()
    //     callback.blockForResponse()

    //     // test is now complete
    //     return callback.succeeded
    // }

    fun getProxy(
        proxyType: Proxy.Type,
        host: String,
        port: Int,
    ): Proxy {
        val addr = InetSocketAddress(host, port)
        return Proxy(proxyType, addr)
    }

    fun startTimer() {
        // creating a new timer sets the start time to the current time
        if (timer == null) {
            timer = Timer()
        } else {
            Log.w(TAG, "startTimer() called but timer already started")
        }
    }

    fun stopTimer(): Long {
        timer?.let {
            return it.stop()
        }
        Log.e(TAG, "stopTimer() called but timer not started")
        return 0
    }

    fun timeSpent(): Long {
        timer?.let {
            return it.timeSpent()
        }
        Log.e(TAG, "timeSpent() called but timer not started")
        return 0
    }
}