package org.greatfire.envoy
/*
    This object hold "global" data for Envoy

    It's used to pass settings from the EnvoyConnectWorker to the
    EnvoyInterceptor
*/

// import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Proxy
import java.net.URL
import java.net.InetSocketAddress
import java.io.IOException
import androidx.work.*


class EnvoyNetworking {

    companion object {
        private const val TAG = "EnvoyNetworking"

        var envoyEnabled = false
        var envoyConnected = false
        var useDirect = false
        var activeUrl: String = ""

        var envoyUrls = mutableListOf<String>()
        var testUrl = "https://www.google.com/generate_204"
        var testResponseCode = 204
        var directUrl = ""
        var concurrency = 2 // XXX

        @JvmStatic
        fun addEnvoyUrl(url: String): Companion {
            envoyUrls.add(url)

            return Companion
        }

        @JvmStatic
        fun setTestUrl(url: String, responseCode: Int): Companion {
            testUrl = url
            testResponseCode = responseCode

            return Companion
        }

        @JvmStatic
        fun setDirect(newVal: Boolean) {
            useDirect = newVal
        }

//        @JvmStatic
//        fun setActiveUrl(newVal: String) {
//            activeUrl = newVal
//        }

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

            val request = Request.Builder().url(testUrl).head().build()

            return runTest(request, null)
        }

        @JvmStatic
        // Test a standard SOCKS or HTTP(S) proxy
        fun testStandardProxy(proxyUrl: URL): Boolean {
            Log.d(TAG, "Testing standard proxy")

            var proxyType = Proxy.Type.HTTP
            if (proxyUrl.getProtocol() == "socks5") {
                proxyType = Proxy.Type.SOCKS
            }
            val addr = InetSocketAddress(proxyUrl.getHost(), proxyUrl.getPort())
            val proxy = Proxy(proxyType, addr)
            val request = Request.Builder().url(testUrl).head().build()

            return runTest(request, proxy)
        }

        @JvmStatic
        // Test using an Envoy HTTP(s) proxy
        // see examples at https://github.com/greatfire/envoy/
        fun testEnvoyOkHttp(proxyUrl: URL): Boolean {
            if (proxyUrl.getProtocol() == "envoy") {
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