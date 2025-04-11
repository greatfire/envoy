package org.greatfire.envoy
/*
    This object hold "global" data for Envoy

    It's used to pass settings from the EnvoyConnectWorker to the
    EnvoyInterceptor
*/

// import android.content.Context
import android.util.Log
// import okhttp3.dnsoverhttps.DnsOverHttps
import okhttp3.HttpUrl.Companion.toHttpUrl
// import java.net.InetAddress
import androidx.work.*
// Go library
import plenipotentiary.Plenipotentiary


class EnvoyNetworking {

    companion object {
        private const val TAG = "EnvoyNetworking"

        // Is Envoy enabled - enable the EnvoyInterceptor
        var envoyEnabled = false
        // Internal state: are we connected
        var envoyConnected = false
        // Internal state: if true, the interceptor passes requets through
        var useDirect = false
        // Internal state: active Envoy or Proxy URL
        // XXX in some cases we need to use both a proxy and an Envoy URL
        var activeUrl: String = ""
        // this value is essentially meaningless before we try connecting
        var activeType: String = ENVOY_PROXY_DIRECT

        var appConnectionsWorking = false

        // How many coroutines to use to test URLs
        var concurrency = 2 // XXX

        private val plen = Plenipotentiary.newPlenipotentiary()

        @JvmStatic
        fun addEnvoyUrl(url: String): Companion {
            EnvoyConnectionTests.addEnvoyUrl(url)

            // let Java callers chain
            return Companion
        }

        // use a custom test URL and response code
        // default is ("https://www.google.com/generate_204", 204)
        @JvmStatic
        fun setTestUrl(url: String, responseCode: Int): Companion {
            EnvoyConnectionTests.testUrl = url
            EnvoyConnectionTests.testResponseCode = responseCode

            return Companion
        }

        // Set the direct URL to the site, if this one works, Envoy is disabled
        @JvmStatic
        fun setDirectUrl(newVal: String): Companion {
            EnvoyConnectionTests.directUrl = newVal

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
            // sets envoyEnabled = true as a side effect
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

    }
}