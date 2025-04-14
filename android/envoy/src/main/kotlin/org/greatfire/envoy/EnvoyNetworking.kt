package org.greatfire.envoy
/*
    This object hold "global" data for Envoy

    It's used to pass settings from the EnvoyConnectWorker to the
    EnvoyInterceptor
*/

// import android.content.Context
import android.util.Log
import okhttp3.*
import okhttp3.dnsoverhttps.DnsOverHttps
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.net.Proxy
import java.net.URI
import java.net.URL
import java.net.InetSocketAddress
import java.net.InetAddress
import java.io.IOException
import androidx.work.*

data class EnvoyTest(
    var testType: String,
    var url: String,
)

class EnvoyNetworking {

    companion object {
        private const val TAG = "EnvoyNetworking"

        // MNB: make private because of various companion methods?
        var envoyEnabled = false
        var envoyConnected = false
        var useDirect = false
        var activeUrl: String = ""
        // this value is essentially meaningless before we try connecting
        var activeType: String = ENVOY_PROXY_DIRECT // MNB: maybe add "none" option

        var appConnectionsWorking = false

        // var envoyUrls = mutableListOf<String>()
        var envoyTests = mutableListOf<EnvoyTest>()

        var testUrl = "https://www.google.com/generate_204"
        var testResponseCode = 204
        var directUrl = ""
        var concurrency = 2 // XXX

        // MNB: does it make sense for these to be in companion?

        // and an Envoy proxy URL to the list to test
        @JvmStatic
        fun addEnvoyUrl(url: String): Companion { // MNB: if supports all tests, maybe addTestUrl()?
            val uri = URI(url) // MNB: try/catch?  Companion syntax?

            when (uri.getScheme()) {
                "http", "https" -> {
                    envoyTests.add(
                        EnvoyTest(ENVOY_PROXY_OKHTTP_ENVOY, url))
                }
                "hysteria2" -> {
                    envoyTests.add(EnvoyTest(ENVOY_PROXY_HYSTERIA2, url))
                }
                else -> {
                    Log.e(TAG, "Unsupported URL: " + url)
                }
            }

            return Companion
        }

        // add a standard SOCKS5 or HTTPS proxy to the list to test
        @JvmStatic
        fun addProxyUrl(url: String): Companion { // MNB: why separate from above?

            val test = EnvoyTest(ENVOY_PROXY_OKHTTP_PROXY, url)
            envoyTests.add(test)

            return Companion
        }

        // use a custom test URL and response code
        // default is ("https://www.google.com/generate_204", 204)
        @JvmStatic
        fun setTestUrl(url: String, responseCode: Int): Companion {
            testUrl = url
            testResponseCode = responseCode // MNB: some way to make this a range? (maybe just 200 = 200s)

            return Companion
        }

        // Set the direct URL to the site, if this one works, Envoy is disabled
        @JvmStatic
        fun setDirectUrl(newVal: String): Companion {
            directUrl = newVal

            return Companion
        }

        // Clear out everything for suspequent runs
        @JvmStatic
        private fun reset() {
            // reset state variables
            envoyEnabled = true
            envoyConnected = false
            useDirect = false
            activeUrl = ""
        }


        @JvmStatic
        fun connect() {
            reset()
            Log.d(TAG, "Starting Envoy connect...")

            val workRequest = OneTimeWorkRequestBuilder<EnvoyConnectWorker>()
                // connecting to the proxy is a high priority task
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()

            WorkManager
                // .getInstance(myContext)
                .getInstance()
                .enqueue(workRequest)
        }

        // The connection worker found a successful connection
        fun connected(newActiveType: String, newActiveUrl: String) {
            Log.i(TAG, "Envoy Connected!")
            Log.d(TAG, "activeType: " + activeType)
            Log.d(TAG, "URL: " + newActiveUrl)

            envoyConnected = true // 🎉
            activeType = newActiveType
            activeUrl = newActiveUrl
        }

        //////
        //
        // Test related methods... these should probably live in their own class
        //
        private fun runTest(request: Request, proxy: java.net.Proxy?): Boolean {
            val builder = OkHttpClient.Builder();
            if (proxy != null) {
                builder.proxy(proxy)
            }
            // val bootstrapClient = builder.build()
            // val dns = DnsOverHttps.Builder().client(bootstrapClient)
            //     .url("https://dns.google/dns-query".toHttpUrl())
            //     .bootstrapDnsHosts(InetAddress.getByName("8.8.4.4"), InetAddress.getByName("8.8.8.8"))
            //     .build()

            // val client = bootstrapClient.newBuilder().dns(dns).build()

            val client = builder.build()

            Log.d(TAG, "testing request to: " + request.url)

            try {
                val response = client.newCall(request).execute()
                val code = response.code
                Log.d(TAG, "request: " + request + ", got code: " + code) // MNB: i think requests have non-exception failure states, ie: 404
                return(code == testResponseCode)
            } catch (e: IOException) {
                Log.e(TAG, "Test threw an error for request" + request)
                Log.e(TAG, "error: " + e)
                return false
            }
        }

        @JvmStatic
        // Test a direct connection to the target site
        fun testDirectConnection(): Boolean {
            Log.d(TAG, "Testing direct connection")

            // We could just skip this?
            if (appConnectionsWorking) {
                Log.d(TAG, "App Connection are working already")
                // MNB: just return true?
            }

            val request = Request.Builder().url(testUrl).head().build() // MNB: use directUrl?

            return runTest(request, null)
        }

        @JvmStatic
        // Test a standard SOCKS or HTTP(S) proxy
        fun testStandardProxy(proxyUrl: URI): Boolean {
            Log.d(TAG, "Testing standard proxy")

            var proxyType = Proxy.Type.HTTP
            if (proxyUrl.getScheme() == "socks5") {
                proxyType = Proxy.Type.SOCKS
            }
            val addr = InetSocketAddress(proxyUrl.getHost(), proxyUrl.getPort())
            val proxy = Proxy(proxyType, addr)
            val request = Request.Builder().url(testUrl).build()

            return runTest(request, proxy)
        }

        @JvmStatic
        // Test using an Envoy HTTP(s) proxy
        // see examples at https://github.com/greatfire/envoy/
        fun testEnvoyOkHttp(proxyUrl: URI): Boolean {
            if (proxyUrl.getScheme() == "envoy") {
                // XXX handle envoy:// URLs
                Log.e(TAG, "envoy:// URLs aren't supported yet ☹️")
                return false
            } else {
                // assume this is an http(s) Evnoy proxy
                val host = URL(testUrl).getHost()
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
    }
}