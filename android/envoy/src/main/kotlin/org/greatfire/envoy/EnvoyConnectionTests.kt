package org.greatfire.envoy

import android.content.Context
import android.net.Uri
import android.os.ConditionVariable
import android.util.Log
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import kotlinx.coroutines.delay
import okhttp3.*
import org.chromium.net.CronetException
import org.chromium.net.UrlRequest
import org.chromium.net.UrlResponseInfo

/*
    Class to hold all the test functions for testing various
    proxy and connection types
*/

class EnvoyConnectionTests {
    private val settings = EnvoyNetworkingSettings.getInstance()

    companion object {
        private const val TAG = "EnvoyConnectionTests"

        // This list of tests persists
        // should this move to the global state/settings
        // object?
        var envoyTests = mutableListOf<EnvoyTest>()

        var cronetThreadPool = Executors.newCachedThreadPool()

        // these are set by helpers in EnvoyNetworking's companion object
        // Target URL/response code for testing
        var testUrl = "https://www.google.com/generate_204"
        // using a 200 code makes it really easy to get false posatives
        var testResponseCode = 204
        // direct URL to the site for testing
        var directUrl = ""

        // this case is a little complicated, so it has it's own
        // function
        //
        // this URL format is (more or less) documented at
        // https://github.com/greatfire/envoy/tree/master/native
        private fun addEnvoySechemeUrl(url: String) {
            // XXX should this take a Uri as a param?
            val tempUri = Uri.parse(url)
            val realUrl = tempUri.getQueryParameter("url")

            if (realUrl.isNullOrEmpty()) {
                Log.e(TAG, "envoy:// URL missing required `url` parameter")
                return
            }

            // this is the only case where the test.url isn't the
            // caller provided URL.. not sure that matters, but
            // it seems worth calling out the oddity
            //
            // We also can't support all the options (like resolver rules)
            // with OkHttp... should we ignore them and try anyway, or
            // just use Cronet if those features are called for?
            val okTest = EnvoyTest(EnvoyServiceType.OKHTTP_ENVOY, realUrl)
            val crTest = EnvoyTest(EnvoyServiceType.CRONET_ENVOY, realUrl)

            // `header_` params
            tempUri.getQueryParameterNames().forEach {
                if (it.startsWith("header_")) {
                    val name = it
                    val value = tempUri.getQueryParameter(it)
                    // tag, you're "it" ... witch "it" carefully here
                    value?.let {
                        okTest.headers.add(Pair(name, it))
                        crTest.headers.add(Pair(name, it))
                    }
                }
            }

            // `address` param
            val addr = tempUri.getQueryParameter("address")
            addr?.let {
                okTest.address = it
                crTest.address = it
            }

            // 'resolver' param
            val resolver = tempUri.getQueryParameter("resolver")
            resolver?.let {
                // okHttp is never going to support this?
                okTest.resolverRules = it
                crTest.resolverRules = it
            }

            // 'socks5' param
            // it's poorly named, http(s):// proxies are ok too
            val proxyUrl = tempUri.getQueryParameter("socks5")
            proxyUrl?.let {
                okTest.proxyUrl = it
                crTest.proxyUrl = it
            }

            // we don't support the other values with OkHttp (yet?)
            // only Cronet
            with(envoyTests) {
                add(okTest)
                add(crTest)
            }
        }

        // and an Envoy proxy URL to the list to test
        //
        // XXX I'm making up some new schemes here, so we can tell between
        // an HTTPS proxy and an HTTPS Envoy URL (though for the latter
        // we could just require the use for envoy:// urls?)
        @JvmStatic
        fun addEnvoyUrl(url: String) {
            val uri = URI(url)

            Log.d(TAG, "&&& addEnvoyUrl type: " + uri.getScheme())

            when (uri.getScheme()) {
                "http", "https", "envoy+https" -> {

                    // set the scheme to a real one if needed
                    var tempUrl = url
                    if (uri.getScheme() == "envoy+https") {
                        tempUrl = url.replaceFirst("^envoy+https".toRegex(), "https")
                    }

                    with(envoyTests) {
                        // XXX should we always test both?
                        add(EnvoyTest(EnvoyServiceType.OKHTTP_ENVOY, tempUrl))
                        add(EnvoyTest(EnvoyServiceType.CRONET_ENVOY, tempUrl))
                        add(EnvoyTest(EnvoyServiceType.HTTP_ECH, tempUrl))
                    }
                }
                "socks5", "proxy+https" -> {
                    var tempUrl = url
                    if (uri.getScheme() == "proxy+https") {
                        tempUrl = url.replaceFirst(
                            "^proxy+https".toRegex(), "proxy")
                    }

                    with (envoyTests) {
                        add(EnvoyTest(EnvoyServiceType.OKHTTP_PROXY, tempUrl))
                        // add(EnvoyTest(EnvoyServiceType.CRONET_PROXY, tempUrl))
                    }
                }
                "envoy" -> {
                    addEnvoySechemeUrl(url)
                }
                "hysteria2" -> {
                    envoyTests.add(EnvoyTest(EnvoyServiceType.HYSTERIA2, url))
                }
                "v2srtp" -> {
                    envoyTests.add(EnvoyTest(EnvoyServiceType.V2SRTP, url))
                }
                "v2wechat" -> {
                    envoyTests.add(EnvoyTest(EnvoyServiceType.V2WECHAT, url))
                }
                "ss" -> {
                    envoyTests.add(EnvoyTest(EnvoyServiceType.SHADOWSOCKS, url))
                }
                else -> {
                    Log.e(TAG, "Unsupported URL: " + url)
                }
            }
        }
    }

    // helper, given a request and optional proxy, test the connection
    private fun runTest(request: Request, proxy: java.net.Proxy?): Boolean {
        val builder = OkHttpClient.Builder();
        if (proxy != null) {
            builder.proxy(proxy)
        }

        val client = builder.build()

        Log.d(TAG, "testing request to: " + request.url)

        try {
            val response = client.newCall(request).execute()
            val code = response.code
            Log.d(TAG, "request: " + request + ", got code: " + code)
            return(code == testResponseCode)
        } catch (e: Exception) {
            Log.e(TAG, "Test threw an error for request" + request)
            Log.e(TAG, "error: " + e)
            return false
        }
    }

    // Test a direct connection to the target site
    fun testDirectConnection(): Boolean {
        Log.d(TAG, "Testing direct connection")

        val request = Request.Builder().url(testUrl).head().build()

        return runTest(request, null)
    }

    private fun getProxy(proxyType: Proxy.Type, host: String, port: Int): Proxy {
        val addr = InetSocketAddress(host, port)
        return Proxy(proxyType, addr)
    }

    // Test a standard SOCKS or HTTP(S) proxy
    suspend fun testStandardProxy(proxyUrl: URI): Boolean {
        Log.d(TAG, "Testing standard proxy $proxyUrl")

        var proxyType = Proxy.Type.HTTP
        if (proxyUrl.getScheme() == "socks5") {
            proxyType = Proxy.Type.SOCKS
        }
        val proxy = getProxy(proxyType, proxyUrl.getHost(), proxyUrl.getPort())
        val request = Request.Builder().url(testUrl).build()

        return runTest(request, proxy)
    }

    // Test using an Envoy HTTP(s) proxy
    // see examples at https://github.com/greatfire/envoy/
    suspend fun testEnvoyOkHttp(proxyUrl: URI): Boolean {
        if (proxyUrl.getScheme() == "envoy") {
            // XXX handle envoy:// URLs
            Log.e(TAG, "envoy:// URLs aren't supported yet ☹️")
            return false
        } else {
            // assume this is an http(s) Evnoy proxy
            val host = URI(testUrl).getHost()
            // XXX cache param, this is hacky :)
            val t = System.currentTimeMillis()
            val url = proxyUrl.toString() + "?test=" + t
            val request = Request.Builder().url(url).head()
                .addHeader("Url-Orig", testUrl)
                .addHeader("Host-Orig", host)
                .build()

            return runTest(request, null)
        }
    }

    // ECH
    suspend fun testECHProxy(test: EnvoyTest): Boolean {
        Log.d(TAG, "Testing Envoy URL with Emissary: " + test.url)

        test.startService()
        val url = settings.emissary.findEnvoyUrl()
        // XXX this is a weird case, emissary returns a new
        // URL to use
        // if it comes back, it's tested and working
        test.proxyUrl = url
        test.proxyIsEnvoy = true
        Log.d(TAG, "Emissary URL: " + url)
        return true
    }

    // IEnvoyProxy PTs
    suspend fun testHysteria2(test: EnvoyTest): Boolean {
        val addr = test.startService()

        test.proxyUrl = "socks5://$addr"

        Log.d(TAG, "testing hysteria2 at ${test.proxyUrl}")

        val res = testStandardProxy(URI(test.proxyUrl))
        if (res == false) {
            test.stopService()
        }
        return res
    }

    // Shadowsocks
    suspend fun testShadowsocks(test: EnvoyTest): Boolean {
        Log.d(TAG, "Testing Shadowsocks " + test)
        val addr = test.startService()

        Log.d(TAG, "started Shadowsocks $addr")

        // XXX wait until it's up... we need an isItUpYet for kotlin
        delay(2000)

        val res = testStandardProxy(URI(addr))
        if (res == false) {
            test.stopService()
        }
        return res
    }

    suspend fun testV2RaySrtp(test: EnvoyTest): Boolean {
        var addr = test.startService()

        if (addr == "") {
            // The go code doesn't handle failures well, but an empty
            // string here indicates failure
            settings.emissary.stopV2RaySrtp() // probably unnecessary
            return false
        }

        test.proxyUrl = "socks5://$addr"
        Log.d(TAG, "Testing V2Ray SRTP ${test.proxyUrl}")

        val res = testStandardProxy(URI(test.proxyUrl))
        if (res == false) {
            test.stopService()
        }
        return res
    }

    suspend fun testV2RayWechat(test: EnvoyTest): Boolean {
        val addr = test.startService()

        if (addr == "") {
            // The go code doesn't handle failures well, but an empty
            // string here indicates failure
            settings.emissary.stopV2RayWechat() // probably unnecessary
            return false
        }

        test.proxyUrl = "socks5://$addr"
        Log.d(TAG, "testing V2Ray WeChat at ${test.proxyUrl}")

        val res = testStandardProxy(URI(test.proxyUrl))
        if (res == false) {
            test.stopService()
        }
        return res
    }

    /////////////////
    // Cronet section

    inner class TestUrlRequestCallback(
        private val test: EnvoyTest) : UrlRequest.Callback()
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
                Log.d(TAG, "Cronet failed")
            }
            requestDone.open()
        }

        override fun onFailed(
            request: UrlRequest?,
            info: UrlResponseInfo?,
            error: CronetException?,
        ) {
            Log.d(TAG, "Cronet failed: " + error)
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

    suspend fun testCronetEnvoy(test: EnvoyTest, context: Context): Boolean {
        val cronetEngine = CronetNetworking.buildEngine(
            context = context,
            cacheFolder = null, // no cache XXX?
            envoyUrl = null, // don't expect a patched cronet
            strategy = 0, // XXX remove this
            cacheSize = 0, // what are the units here?
        )
        val callback = TestUrlRequestCallback(test)
        // aim at the Envoy proxy instead of the real target
        val requestBuilder = cronetEngine.newUrlRequestBuilder(
            test.url, // Envoy proxy URL
            callback,
            cronetThreadPool
        )
        // add the Envoy headers for the real target
        val targetHost = URI(testUrl).getHost()
        // XXX cache param
        requestBuilder.addHeader("Host-Orig", targetHost)
        requestBuilder.addHeader("Url-Orig", testUrl)

        val request = requestBuilder.build()
        request.start()
        callback.blockForResponse()

        // test is now complete
        return callback.succeeded
    }
}