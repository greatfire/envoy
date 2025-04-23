package org.greatfire.envoy
/*
    This object provides an external interface for setting up network connections with Envoy.
    It can also be accessed from the EnvoyConnectWorker and the EnvoyInterceptor.
*/

import android.content.Context
import android.util.Log
import androidx.work.*
import java.io.File

class EnvoyNetworking {

    companion object {
        private const val TAG = "EnvoyNetworking"

        private fun createCronetEngine() {

            val settings = EnvoyNetworkingSettings.getInstance()

            // I think we can reuse the cache dir between runs?
            val cacheDir = File(settings.ctx!!.cacheDir, "cronet-cache")
            if (!cacheDir.exists()) {
                cacheDir.mkdirs()
            }

            settings.cronetEngine = CronetNetworking.buildEngine(
                context = settings.ctx!!,
                cacheFolder = cacheDir.absolutePath,
                envoyUrl = null,
                strategy = 0,
                cacheSize = 10, // cache size in MB
            )
        }

        // The connection worker found a successful connection
        fun connected(test: EnvoyTest) {

            val settings = EnvoyNetworkingSettings.getInstance()

            Log.i(TAG, "Envoy Connected!")
            Log.d(TAG, "service: " + test.testType)
            Log.d(TAG, "URL: " + test.url)
            Log.d(TAG, "proxyUrl: " + test.proxyUrl)

            settings.realActiveUrl = test.url
            settings.realActiveType = test.testType

            when (test.testType) {
                EnvoyServiceType.DIRECT -> {
                    settings.useDirect = true
                }
                EnvoyServiceType.CRONET_ENVOY -> {
                    // Cronet is selected, create the cronet engine
                    createCronetEngine()
                    settings.activeType = test.testType
                    settings.activeUrl = test.url
                }
                EnvoyServiceType.HTTP_ECH -> {
                    // upstream is an Envoy proxy
                    settings.activeType = EnvoyServiceType.OKHTTP_ENVOY
                    settings.activeUrl = test.proxyUrl!!
                }
                // all these services provice a SOCKS5 proxy
                EnvoyServiceType.HYSTERIA2,
                EnvoyServiceType.V2SRTP,
                EnvoyServiceType.V2WECHAT -> {
                    // we have a SOCKS (or HTTP) proxy at proxy URL
                    settings.activeType = EnvoyServiceType.OKHTTP_PROXY
                    settings.activeUrl = test.proxyUrl!!
                }
                else -> {
                    settings.activeType = test.testType
                    settings.activeUrl = test.url
                }
            }

            settings.envoyConnected = true // 🎉
        }

    }

    // Public functions, this is the primary public interface for Envoy
    fun addEnvoyUrl(url: String): EnvoyNetworking {
        EnvoyConnectionTests.addEnvoyUrl(url)

        // let Java callers chain
        return this
    }

    // use a custom test URL and response code
    // default is ("https://www.google.com/generate_204", 204)
    fun setTestUrl(url: String, responseCode: Int): EnvoyNetworking {
        EnvoyConnectionTests.testUrl = url
        EnvoyConnectionTests.testResponseCode = responseCode

        return this
    }

    // Set the direct URL to the site, if this one works, Envoy is bypassed
    //
    // The caller should either pass a URL here or set passivelyTestDirect
    // direct to true. It's not a problem to do both, but probably not
    // necessary. Doing neither will disable direct connections
    fun setDirectUrl(newVal: String): EnvoyNetworking {
        EnvoyConnectionTests.directUrl = newVal

        return this
    }

    // Set the callback for reporting status to the main application
    fun setCallback(callback: EnvoyTestCallback): EnvoyNetworking {
        val settings = EnvoyNetworkingSettings.getInstance()
        settings.callback = callback

        return this
    }

    // Provide a context reference from the main application
    fun setContext(context: Context): EnvoyNetworking {
        val settings = EnvoyNetworkingSettings.getInstance()
        settings.ctx = context

        return this
    }

    fun connect(): EnvoyNetworking {

        // sets envoyEnabled = true as a side effect

        val settings = EnvoyNetworkingSettings.getInstance()

        settings.resetState()
        Log.d(TAG, "Starting Envoy connect...")

        val workRequest = OneTimeWorkRequestBuilder<EnvoyConnectWorker>()
            // connecting to the proxy is a high priority task
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()

        WorkManager
            .getInstance()
            .enqueue(workRequest)

        return this
    }
}