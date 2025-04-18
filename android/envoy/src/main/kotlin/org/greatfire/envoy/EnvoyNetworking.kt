package org.greatfire.envoy
/*
    This object hold "global" data for Envoy

    It's used to pass settings from the EnvoyConnectWorker to the
    EnvoyInterceptor
*/

import android.content.Context
import android.util.Log
import androidx.work.*
import okhttp3.HttpUrl.Companion.toHttpUrl
// Go library
import emissary.Emissary

class EnvoyNetworking {

    companion object {
        private const val TAG = "EnvoyNetworking"

        // MNB: make private because of various companion methods?

        // Settings
        //
        // How many coroutines to use to test URLs
        var concurrency = 2 // XXX

        // this stuff is "internal" but public because
        // the connection worker and tests mess with it
        //
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
        var activeType: String = ENVOY_PROXY_DIRECT // MNB: maybe add "none" option

        var appConnectionsWorking = false

        // val plen = Plenipotentiary.newPlenipotentiary()

        val emissary = Emissary.newEmissary()

        val dns = EnvoyDns()

        // MNB: does it make sense for these to be in companion?

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
        fun connect(context: Context, callback: EnvoyTestCallback) {
            // sets envoyEnabled = true as a side effect
            reset()
            Log.d(TAG, "Starting Envoy connect...")

            val workRequest = OneTimeWorkRequestBuilder<EnvoyConnectWorker>()
                // connecting to the proxy is a high priority task
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()


            val config = Configuration.Builder()
                .setWorkerFactory(EnvoyConnectWorkerFactory(callback))
                .build()

            WorkManager.initialize(context, config)
            WorkManager
                // .getInstance(getApplicationContext())
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
    }
}