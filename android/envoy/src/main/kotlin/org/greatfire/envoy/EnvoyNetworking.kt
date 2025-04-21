package org.greatfire.envoy
/*
    This object hold "global" data for Envoy

    It's used to pass settings from the EnvoyConnectWorker to the
    EnvoyInterceptor
*/

import android.content.Context
import android.util.Log
import androidx.work.*
import java.io.File
import okhttp3.HttpUrl.Companion.toHttpUrl
// Cronet
import org.chromium.net.CronetEngine
// Go library
import emissary.Emissary

class EnvoyNetworking {

    companion object {
        private const val TAG = "EnvoyNetworking"

        // MNB: make private because of various companion methods?
        // How do we make them private and accessible from the worker and the interceptor?

        // Settings
        //
        // How many coroutines to use to test URLs
        var concurrency = 6 // XXX

        // Should the Interceptor enable a direct connection if the app
        // requests appear to be working (i.e. returning 200 codes)
        var passivelyTestDirect = true

        // Is Envoy enabled - enable the EnvoyInterceptor
        // This gets set to `true` when Envoy is initialized, but the caller
        // could set it back to `false` if the want to disable Envoy for
        // some reason
        var envoyEnabled = false

        // this stuff is "internal" but public because
        // the connection worker and tests mess with it
        //
        // Internal state: are we connected
        var envoyConnected = false
        // Internal state: if true, the interceptor passes requets through
        var useDirect = false
        // Internal state: active Envoy or Proxy URL
        // XXX in some cases we need to use both a proxy and an Envoy URL
        var activeUrl: String = ""
        // this value is essentially meaningless before we try connecting
        var activeType = EnvoyServiceType.UNKNOWN

        // XXX these are dumb names, but currently activeType needs to be a
        // generic SOCKS5 proxy for most protocols, so store the "real" one
        // here for now
        var realActiveType = EnvoyServiceType.UNKNOWN
        var realActiveUrl: String = ""

        var appConnectionsWorking = false

        // our Cronet instance
        var cronetEngine: CronetEngine? = null

        // Go library
        val emissary = Emissary.newEmissary()

        // DNS related code
        val dns = EnvoyDns()

        private var ctx: Context? = null


        private fun createCronetEngine() {
            // I think we can reuse the cache dir between runs?
            val cacheDir = File(ctx!!.cacheDir, "cronet-cache")
            if (!cacheDir.exists()) {
                cacheDir.mkdirs()
            }

            cronetEngine = CronetNetworking.buildEngine(
                context = ctx!!,
                cacheFolder = cacheDir.absolutePath,
                envoyUrl = null,
                strategy = 0,
                cacheSize = 10, // What unit is this? MB?
            )
        }

        // The connection worker found a successful connection
        fun connected(test: EnvoyTest) {
            Log.i(TAG, "Envoy Connected!")
            Log.d(TAG, "service: " + test.testType)
            Log.d(TAG, "URL: " + test.url)
            Log.d(TAG, "proxyUrl: " + test.proxyUrl)

            realActiveUrl = test.url
            realActiveType = test.testType

            when (test.testType) {
                EnvoyServiceType.DIRECT -> {
                    useDirect = true
                }
                EnvoyServiceType.CRONET_ENVOY -> {
                    // Cronet is selected, create the cronet engine
                    createCronetEngine()
                    activeType = test.testType
                    activeUrl = test.url
                }
                EnvoyServiceType.HTTP_ECH -> {
                    // upstream is an Envoy proxy
                    activeType = EnvoyServiceType.OKHTTP_ENVOY
                    activeUrl = test.proxyUrl!!
                }
                // all these services provice a SOCKS5 proxy
                EnvoyServiceType.HYSTERIA2,
                EnvoyServiceType.V2SRTP,
                EnvoyServiceType.V2WECHAT -> {
                    // we have a SOCKS (or HTTP) proxy at proxy URL
                    activeType = EnvoyServiceType.OKHTTP_PROXY
                    activeUrl = test.proxyUrl!!
                }
                else -> {
                    activeType = test.testType
                    activeUrl = test.url
                }
            }

            envoyConnected = true // 🎉
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

    fun connect(context: Context, callback: EnvoyTestCallback): EnvoyNetworking {
        // sets envoyEnabled = true as a side effect
        ctx = context // this probably makes me a bad person ;-)

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

        return this
    }


    // Clear out everything for suspequent runs
    private fun reset() {
        // reset state variables
        envoyEnabled = true
        envoyConnected = false
        useDirect = false
        activeUrl = ""
        activeType = EnvoyServiceType.UNKNOWN
        realActiveUrl = ""
        realActiveType = EnvoyServiceType.UNKNOWN
    }
}