package org.greatfire.envoy

import android.content.Context
import android.net.Uri
import android.os.ConditionVariable
import android.util.Log
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
// import java.net.URI
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import javax.net.ssl.*
import kotlinx.coroutines.delay
import okhttp3.*
import org.chromium.net.CronetException
import org.chromium.net.UrlRequest
import org.chromium.net.UrlResponseInfo
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

/*
    Class to hold all the test functions for testing various
    proxy and connection types
*/

class EnvoyConnectionTests {

    companion object {
        private const val TAG = "EnvoyConnectionTests"

        var directTest: EnvoyDirectTest? = null;

        // This list of tests persists
        // should this move to the global state/settings
        // object?
        var envoyTests = mutableListOf<EnvoyTest>()

        var cronetThreadPool = Executors.newCachedThreadPool()

        // these are set by helpers in EnvoyNetworking's companion object
        // Target URL/response code for testing
        var testUrl = "https://www.google.com/generate_204"
        // using a 200 code makes it really easy to get false positives
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
            // XXX should we preserve the original envoy:// URL?
            //
            // We also can't support all the options (like resolver rules)
            // with OkHttp... should we ignore them and try anyway, or
            // just use Cronet if those features are called for?
            val okTest = EnvoyHttpEnvoyTest(realUrl, testUrl, testResponseCode)
            val crTest = EnvoyCronetEnvoyTest(realUrl, testUrl, testResponseCode)
            val echTest = EnvoyHttpEchTest(realUrl, testUrl, testResponseCode)

            // `header_` params
            tempUri.getQueryParameterNames().forEach {
                if (it.startsWith("header_")) {
                    val value = tempUri.getQueryParameter(it)

                    // strip off the "header_" prefix
                    val parts = it.split("_", limit = 2)
                    val name = parts[1]
                    // tag, you're "it" ... witch "it" carefully here
                    Log.d(TAG, "adding global header: $name: $value")
                    value?.let {
                        okTest.headers.add(Pair(name, it))
                        crTest.headers.add(Pair(name, it))
                        echTest.headers.add(Pair(name, it))
                    }
                }
            }

            // 'resolver' param
            tempUri.getQueryParameter("resolver")?.let {
                // OkHttp is never going to support this?
                okTest.resolverRules = it
                crTest.resolverRules = it
                echTest.resolverRules = it
            }

            // `address` param
            tempUri.getQueryParameter("address")?.let {
                // this is a shortcut for creating a ResolverRule
                // for the `url` param
                val temp = Uri.parse(realUrl)
                val host = temp.getHost()

                val rule = "MAP $host $it"

                // support both `resolver` and `address`
                // the were mutually exclusive in the C++ patches,
                // but they don't need to be
                if (crTest.resolverRules != null) {
                    crTest.resolverRules += (',' + rule)
                } else {
                    crTest.resolverRules = rule
                }

                // Our OkHttp code doesn't support these, but maybe in the
                // future...
                if (okTest.resolverRules != null) {
                    okTest.resolverRules += (',' + rule)
                } else {
                    okTest.resolverRules = rule
                }
                if (echTest.resolverRules != null) {
                    echTest.resolverRules += (',' + rule)
                } else {
                    echTest.resolverRules = rule
                }

                // currently unused, but stash away the value
                okTest.address = it
                crTest.address = it
                echTest.address = it
            }

            // 'socks5' param
            // it's poorly named, http(s):// proxies are ok too
            tempUri.getQueryParameter("socks5")?.let {
                okTest.proxyUrl = it
                crTest.proxyUrl = it
                echTest.proxyUrl = it
            }

            with(envoyTests) {
                add(okTest)
                add(crTest)
                add(echTest)
            }
        }

        @JvmStatic
        fun addDirectUrl(url: String) {
            directUrl = url;
            directTest = EnvoyDirectTest(url, testUrl, testResponseCode)
        }

        // and an Envoy proxy URL to the list to test
        //
        // This should probably live somewhere else... it's here because
        // this was the best place to put it at the time :)
        //
        // XXX I'm making up some new schemes here, so we can tell between
        // an HTTPS proxy and an HTTPS Envoy URL (though for the latter
        // we could just require the use for envoy:// urls?)
        @JvmStatic
        fun addEnvoyUrl(url: String) {
            val uri = Uri.parse(url)

            Log.d(TAG, "&&& addEnvoyUrl type: " + uri.getScheme())

            when (uri.getScheme()) {
                "http", "https", "envoy+https" -> {

                    // set the scheme to a real one if needed
                    var tempUrl = url
                    if (uri.scheme == "envoy+https") {
                        tempUrl = url.replaceFirst("""^envoy\+https""".toRegex(), "https")
                    }

                    with(envoyTests) {
                        // XXX should we always test both?
                        add(EnvoyHttpEnvoyTest(tempUrl, testUrl, testResponseCode))
                        add(EnvoyCronetEnvoyTest(tempUrl, testUrl, testResponseCode))
                        add(EnvoyHttpEchTest(tempUrl, testUrl, testResponseCode))
                    }
                }

                "masque" -> {
                    with(envoyTests) {
                        // add(EnvoyTest(EnvoyServiceType.OKHTTP_MASQUE, url))
                        // add(EnvoyTest(EnvoyServiceType.CRONET_MASQUE, url))
                        add(EnvoyHttpMasqueTest(url, testUrl, testResponseCode))
                        add(EnvoyCronetMasqueTest(url, testUrl, testResponseCode))
                    }
                }
                // These aren't "officially" supported by Envoy, but they're
                // easy to support
                "socks5", "proxy+http" -> {
                    val tempUrl = when(uri.scheme) {
                        "proxy+https" -> url.replaceFirst(
                            """^proxy\+http""".toRegex(), "http")
                        // OkHttp doesn't yet support HTTPS CONNECT proxies (!)
                        // https://github.com/square/okhttp/issues/8373
                        else -> url
                    }

                    Log.d(TAG, "proxy URL: $tempUrl")

                    with (envoyTests) {
                        // add(EnvoyTest(EnvoyServiceType.OKHTTP_PROXY, tempUrl))
                        // add(EnvoyTest(EnvoyServiceType.CRONET_PROXY, tempUrl))
                        add(EnvoyHttpProxyTest(tempUrl, testUrl, testResponseCode))
                        add(EnvoyCronetProxyTest(tempUrl, testUrl, testResponseCode))
                    }
                }
                "envoy" -> {
                    addEnvoySechemeUrl(url)
                }
                "hysteria2" -> {
                    // envoyTests.add(EnvoyTest(EnvoyServiceType.HYSTERIA2, url))
                    envoyTests.add(EnvoyHysteriaTest(url, testUrl, testResponseCode))
                }
                "v2srtp" -> {
                    // envoyTests.add(EnvoyTest(EnvoyServiceType.V2SRTP, url))
                    envoyTests.add(EnvoyV2srtpTest(url, testUrl, testResponseCode))
                }
                "v2wechat" -> {
                    // envoyTests.add(EnvoyTest(EnvoyServiceType.V2WECHAT, url))
                    envoyTests.add(EnvoyV2wechatTest(url, testUrl, testResponseCode))
                }
                "ss" -> {
                    // envoyTests.add(EnvoyTest(EnvoyServiceType.SHADOWSOCKS, url))
                    envoyTests.add(EnvoyShadowsocksTest(url, testUrl, testResponseCode))
                }
                else -> {
                    Log.e(TAG, "Unsupported URL: " + url)
                }
            }
        }

        // helper, some services return "host:port"
        suspend fun isItUpYet(addr: String): Boolean {
            val parts = addr.split(":")
            if (parts.size > 1) {
                return isItUpYet(parts[0], parts[1].toInt())
            }
            return false
        }

        // This should live elsewhere
        // poll until a TCP port is listening, so we can use
        // services as soon as they're up
        suspend fun isItUpYet(host: String, port: Int): Boolean {
            // Give up at some point
            val OVERALL_TIMEOUT = 5 * 1000
            // Length between tests
            val POLL_INTERVAL = 1000L

            val startTime = System.currentTimeMillis()

            while (true) {
                // check OVERALL_TIMEOUT
                if (System.currentTimeMillis() - startTime > OVERALL_TIMEOUT) {
                    Log.e(TAG, "Service at $host:$port didn't start in time")
                    return false
                }

                // no timeout, we just want to see if the port is open
                try {
                    // val sock = Socket(host, port, 0)
                    val sock = Socket()
                    // this needs some actual time to connect
                    sock.connect(InetSocketAddress(host, port), 1000)
                    Log.d(TAG, "UP! $host:$port")
                    return true
                } catch (e: Exception) {
                    // should be a java.net.ConnectException
                    // should we test that?
                    Log.d(TAG, "Not up yet $host:$port, $e")
                }
                delay(POLL_INTERVAL)
            }

            // this shouldn't be reachable
            return false
        }
    }

    //private val state = EnvoyState.getInstance()

    // helper, given a request and optional proxy, test the connection
    /*
    private fun runTest(request: Request, proxy: java.net.Proxy?): Boolean {
        val builder = OkHttpClient.Builder();
        if (proxy != null) {
              builder.proxy(proxy)
        }

        val client = builder.callTimeout(30, TimeUnit.SECONDS).build()

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
    */

    // Test a direct connection to the target site
    /*
    fun testDirectConnection(): Boolean {
        Log.d(TAG, "Testing direct connection")

        val request = Request.Builder().url(testUrl).head().build()

        return runTest(request, null)
    }
    */

    /*
    private fun getProxy(
        proxyType: Proxy.Type,
        host: String,
        port: Int,
    ): Proxy {
        val addr = InetSocketAddress(host, port)
        return Proxy(proxyType, addr)
    }
    */

    // Test a standard SOCKS or HTTP proxy
    // !! OkHttp does not support HTTPS (yet)
    /*
    suspend fun testStandardProxy(proxyUri: Uri): Boolean {
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
    */

    // Test using an Envoy HTTP(s) proxy
    // see examples at https://github.com/greatfire/envoy/
    /*
    suspend fun testEnvoyOkHttp(proxyUrl: Uri): Boolean {

        val host = Uri.parse(testUrl).host
        if (host == null) {
            Log.e(TAG, "Test URL has no host!?")
            return false
        }

        // XXX cache param, this is hacky :)
        // this nshould be updated to use the same checksum param
        // that the C++ patches used to use
        val t = System.currentTimeMillis()
        val url = proxyUrl.toString() + "?test=" + t
        val request = Request.Builder()
            .url(url)
            // .head()  // a HEAD request is enough to test it works
            .addHeader("Url-Orig", testUrl)
            .addHeader("Host-Orig", host)
            .build()

        return runTest(request, null)
    }
    */

    // ECH
    /*
    suspend fun testECHProxy(test: EnvoyTest): Boolean {
        Log.d(TAG, "Testing Envoy URL with IEnvoyProxy: " + test)

        test.startService()
        // XXX this is a weird case, IEP returns a new
        // URL to use
        // if it comes back, it's tested and working
        val url = test.getEnvoyUrl()

        if (url.isNullOrEmpty()) {
            return false
        }

        test.proxyUrl = url
        test.proxyIsEnvoy = true
        Log.d(TAG, "IEP Envoy URL: " + test.proxyUrl)
        return true
    }
    */

    // MASQUE
    /*
    suspend fun testMasqueOkHttp(test: EnvoyTest): Boolean {
        if (test.proxyUrl == null) {
            // the other test hasn't started it yet
            val addr = test.startService()
            test.proxyUrl = "http://$addr"
            Log.d(TAG, "Starting MASQUE: $addr")
        }

        return testStandardProxy(Uri.parse(test.proxyUrl))
    }
    */

    // MASQUE Cronet XXX does Cronet support this natively?
    /*
    suspend fun testMasqueCronet(test: EnvoyTest, context: Context): Boolean {
        if (test.proxyUrl == null) {
            // the other test hasn't started it yet
            val addr = test.startService()
            test.proxyUrl = "http://$addr"
            Log.d(TAG, "Starting MASQUE: $addr")
        }

        return testCronetProxy(test, context)
    }
    */

    // IEnvoyProxy PTs
    /*
    suspend fun testHysteria2(test: EnvoyTest): Boolean {
        val addr = test.startService()

        test.proxyUrl = "socks5://$addr"

        Log.d(TAG, "testing hysteria2 at ${test.proxyUrl}")

        val res = testStandardProxy(Uri.parse(test.proxyUrl))
        if (res == false) {
            test.stopService()
        }
        return res
    }
    */

    // Shadowsocks
    /*
    suspend fun testShadowsocks(test: EnvoyTest): Boolean {
        Log.d(TAG, "Testing Shadowsocks " + test)
        val addr = test.startService()

        // this already has the socks5:// prefix, I guess
        // we're not consistent there :)
        test.proxyUrl = addr

        Log.d(TAG, "testing Shadowsocks $addr")

        val res = testStandardProxy(Uri.parse(addr))
        // if (res == false) {
        //     test.stopService()
        // }
        return res
    }
    */

    /*
    suspend fun testV2RaySrtp(test: EnvoyTest): Boolean {
        var addr = test.startService()

        if (addr == "") {
            // The go code doesn't handle failures well, but an empty
            // string here indicates failure
            test.stopService()
            return false
        }

        test.proxyUrl = "socks5://$addr"
        Log.d(TAG, "Testing V2Ray SRTP ${test.proxyUrl}")

        val res = testStandardProxy(Uri.parse(test.proxyUrl))
        if (res == false) {
            test.stopService()
        }
        return res
    }
    */

    /*
    suspend fun testV2RayWechat(test: EnvoyTest): Boolean {
        val addr = test.startService()

        if (addr == "") {
            // The go code doesn't handle failures well, but an empty
            // string here indicates failure
            test.stopService()
            return false
        }

        test.proxyUrl = "socks5://$addr"
        Log.d(TAG, "testing V2Ray WeChat at ${test.proxyUrl}")

        val res = testStandardProxy(Uri.parse(test.proxyUrl))
        if (res == false) {
            test.stopService()
        }
        return res
    }
    */

    /////////////////
    // Cronet section

    /*
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
    */

    /*
    suspend fun testCronetEnvoy(test: EnvoyTest, context: Context): Boolean {
        // proxyUrl might be an Envoy proxy or a standard (socks/http) proxy
        var proxyUrl: String? = null
        if (test.proxyUrl != null && !test.proxyIsEnvoy) {
            proxyUrl = test.proxyUrl
        }

        val cronetEngine = CronetNetworking.buildEngine(
            context = context,
            cacheFolder = null, // no cache XXX?
            proxyUrl = proxyUrl,
            resolverRules = test.resolverRules,
            cacheSize = 0, // cache size in MB
        )
        val callback = TestUrlRequestCallback()
        // aim at the Envoy proxy instead of the real target
        val requestBuilder = cronetEngine.newUrlRequestBuilder(
            test.url, // Envoy proxy URL
            callback,
            cronetThreadPool
        )
        // add the Envoy headers for the real target
        val targetHost = Uri.parse(testUrl).host
        // XXX cache param
        requestBuilder.addHeader("Host-Orig", targetHost)
        requestBuilder.addHeader("Url-Orig", testUrl)

        val request = requestBuilder.build()
        request.start()
        callback.blockForResponse()

        // test is now complete
        return callback.succeeded
    }
    */

    /*
    suspend fun testCronetProxy(test: EnvoyTest, context: Context): Boolean {
        var proxyUrl = test.url
        // proxyIsEnvoy should never be true here?
        if (test.proxyUrl != null && !test.proxyIsEnvoy) {
            proxyUrl = test.proxyUrl!!
        }

        val cronetEngine = CronetNetworking.buildEngine(
            context = context,
            cacheFolder = null, // no cache XXX?
            proxyUrl = proxyUrl,
            resolverRules = test.resolverRules,
            cacheSize = 0, // what are the units here?
        )
        val callback = TestUrlRequestCallback()
        // We're just making a standard request via Cronet to a standard proxy
        val requestBuilder = cronetEngine.newUrlRequestBuilder(
            testUrl,
            callback,
            cronetThreadPool
        )

        val request = requestBuilder.build()
        request.start()
        callback.blockForResponse()

        // test is now complete
        return callback.succeeded
    }
    */
}