package org.greatfire.envoy

import android.net.UrlQuerySanitizer
import android.util.Log
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.net.URL
import okhttp3.*

/*
    Class to hold all the test functions for testing various
    proxy and connection types
*/

data class EnvoyTest(
    var testType: String,
    var url: String,
) {
    var extra: String? = null
}

class EnvoyConnectionTests {
    companion object {
        private const val TAG = "EnvoyConnectionTests"

        // var envoyUrls = mutableListOf<String>()
        // This list of tests persists
        var envoyTests = mutableListOf<EnvoyTest>()

        // Target URL/response code for testing
        var testUrl = "https://www.google.com/generate_204"
        // using a 200 code makes it really easy to get false posatives
        var testResponseCode = 204
        // direct URL to the site for testing
        var directUrl = ""

        // and an Envoy proxy URL to the list to test
        //
        // XXX I'm making up some new schemes here, so we can tell between
        // an HTTPS proxy and an HTTPS Envoy URL (though for the latter
        // we could just require the use for envoy:// urls?)
        @JvmStatic
        fun addEnvoyUrl(url: String) {
            val uri = URI(url)

            when (uri.getScheme()) {
                "http", "https", "envoy+https" -> {

                    // set the scheme to a real one if needed
                    var tempUrl = url
                    if (uri.getScheme() == "envoy+https") {
                        tempUrl = url.replaceFirst("^envoy+https".toRegex(), "https")
                    }

                    with(envoyTests) {
                        // XXX should we always test both?
                        // add(EnvoyTest(ENVOY_PROXY_OKHTTP_ENVOY, tempUrl))
                        // add(EnvoyTest(ENVOY_PROXY_CRONET_ENVOY, tempUrl))
                        add(EnvoyTest(ENVOY_PROXY_HTTP_ECH, tempUrl))
                    }
                }
                "socks5", "proxy+https" -> {
                    var tempUrl = url
                    if (uri.getScheme() == "proxy+https") {
                        tempUrl = url.replaceFirst(
                            "^proxy+https".toRegex(), "proxy")
                    }

                    with (envoyTests) {
                        add(EnvoyTest(ENVOY_PROXY_OKHTTP_PROXY, tempUrl))
                        // add(EnvoyTest(ENVOY_PROXY_CRONET_PROXY, tempUrl))
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
        }

        // helper, given a request and optional proxy, test the connection
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
            if (EnvoyNetworking.appConnectionsWorking) {
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
        fun testECHProxy(test: EnvoyTest): Boolean {
            Log.d(TAG, "Testing Envoy URL with Plenipotentiary: " + test.url)
            val url = EnvoyNetworking.plen.findEnvoyUrl(test.url)
            // XXX this is a weird case, plenipotentiary returns a new
            // URL to use
            // if it comes back, it's tested and working
            test.extra = url
            Log.d(TAG, "Plen URL: " + url)
            return true
        }

        @JvmStatic
        fun testHysteria2(proxyUrl: URI): Boolean {
            // XXX the Go code needs some improvement
            return false

            /*
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
            */
        }

    }
}