package org.greatfire.envoy
/*
    This object hold "global" data for Envoy

    It's used to pass settings from the EnvoyConnectWorker to the
    EnvoyInterceptor
*/

// import android.content.Context
import android.net.UrlQuerySanitizer
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
// Go library
import plenipotentiary.Plenipotentiary

data class EnvoyTest(
    var testType: String,
    var url: String,
)

class EnvoyNetworking {

    companion object {
        private const val TAG = "EnvoyNetworking"

        var envoyEnabled = false
        var envoyConnected = false
        var useDirect = false
        var activeUrl: String = ""
        // this value is essentially meaningless before we try connecting
        var activeType: String = ENVOY_PROXY_DIRECT

        var appConnectionsWorking = false

        // var envoyUrls = mutableListOf<String>()
        var envoyTests = mutableListOf<EnvoyTest>()

        var testUrl = "https://www.google.com/generate_204"
        var testResponseCode = 204
        var directUrl = ""
        var concurrency = 2 // XXX

        private val plen = Plenipotentiary.newPlenipotentiary()

        // and an Envoy proxy URL to the list to test
        @JvmStatic
        fun addEnvoyUrl(url: String): Companion {
            val uri = URI(url)

            when (uri.getScheme()) {
                "http", "https", "envoy+https" -> {
                    with(envoyTests) {
                        // XXX should we always test both?
                        add(EnvoyTest(ENVOY_PROXY_OKHTTP_ENVOY, url))
                        // add(EnvoyTest(ENVOY_PROXY_CRONET_ENVOY, url))
                    }
                }
                "socks5", "proxy+https" -> {
                    with (envoyTests) {
                        add(EnvoyTest(ENVOY_PROXY_OKHTTP_PROXY, url))
                        // add(EnvoyTest(ENVOY_PROXY_CRONET_PROXY, url))
                    }
                }
                "envoy" -> {
                    val san = UrlQuerySanitizer()
                    san.setAllowUnregisteredParamaters(true)
                    san.parseUrl(url)

                    // https://github.com/greatfire/envoy/blob/master/native/README.md
                    val eUrl = san.getValue("url")
                    // we don't support the other values with OkHttp (yet?)
                    // only Cronet
                    with(envoyTests) {
                        add(EnvoyTest(ENVOY_PROXY_OKHTTP_ENVOY, eUrl))
                        // add(EnvoyTest(ENVOY_PROXY_CRONET_ENVOY, url))
                    }
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
        fun addProxyUrl(url: String): Companion {

            val test = EnvoyTest(ENVOY_PROXY_OKHTTP_PROXY, url)
            envoyTests.add(test)

            return Companion
        }

        // use a custom test URL and response code
        // default is ("https://www.google.com/generate_204", 204)
        @JvmStatic
        fun setTestUrl(url: String, responseCode: Int): Companion {
            testUrl = url
            testResponseCode = responseCode

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
                Log.d(TAG, "request: " + request + ", got code: " + code)
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
            }

            val request = Request.Builder().url(testUrl).head().build()

            return runTest(request, null)
        }

        @JvmStatic
        private fun getProxy(proxyType: Proxy.Type, host: String, port: Int): Proxy {
            val addr = InetSocketAddress(host, port)
            return Proxy(proxyType, addr)
        }

        @JvmStatic
        // Test a standard SOCKS or HTTP(S) proxy
        fun testStandardProxy(proxyUrl: URI): Boolean {
            Log.d(TAG, "Testing standard proxy")

            var proxyType = Proxy.Type.HTTP
            if (proxyUrl.getScheme() == "socks5") {
                proxyType = Proxy.Type.SOCKS
            }
            val proxy = getProxy(proxyType, proxyUrl.getHost(), proxyUrl.getPort())
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

        @JvmStatic
        fun testHysteria2(proxyUrl: URI): Boolean {
            plen.controller.hysteriaServer(proxyUrl)
            plen.controller.start(plen.controller.Hysteria2)
            val port = plen.controller.getPort(plen.controller.Hysteria2)
            val proxy = getProxy(Proxy.Type.SOCKS, "localhost", port)

            val request = Request.Builder().url(testUrl).build()

            res = runTest(request, proxy)

            if (res) {
                // leave Hysteria running if it was successful
                // XXX should probably manage this better
            } else {
                plen.controller.stop(plen.controller.Hysteria2)
            }
        }
    }
}