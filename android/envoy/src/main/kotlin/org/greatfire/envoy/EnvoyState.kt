package org.greatfire.envoy

import android.content.Context
import android.util.Log
import emissary.Emissary // Envoy Go library
import java.io.File
import org.chromium.net.CronetEngine

class EnvoyState private constructor() {

    companion object {
        private const val TAG = "EnvoyState"

        // Singleton
        @Volatile
        private var instance: EnvoyState? = null

        fun getInstance() = instance ?: synchronized(this) {
            instance ?: EnvoyState().also { instance = it }
        }
    }

    // Settings
    //
    // How many coroutines to use to test URLs
    // this effectively limits the number of requests we can make at once
    // while testing
    var concurrency = 6 // XXX

    // util class now handles test results

    // our Cronet engine
    var cronetEngine: CronetEngine? = null

    // interface with application
    var callback: EnvoyTestCallback? = null

    // can we get away without this?
    // maybe a callback method to return context?
    var ctx: Context? = null

    // Go library
    val emissary = Emissary.newEmissary()

    // DNS related code
    val dns = EnvoyDns()

    val shadowsocks: ShadowsocksService? = null

    private fun createCronetEngine() {
        // I think we can reuse the cache dir between runs?
        // XXX we used to have multiple tests cronet based tests
        // running in parallel...
        val cacheDir = File(ctx!!.cacheDir, "cronet-cache")
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }

        cronetEngine = CronetNetworking.buildEngine(
            context = ctx!!,
            cacheFolder = cacheDir.absolutePath,
            envoyUrl = null,
            strategy = 0,
            cacheSize = 10, // cache size in MB
        )
    }

    // called when the connection worker found a successful connection
    fun connectIfNeeded(test: EnvoyTest) {
        if (test.selectedService && test.testType == EnvoyServiceType.CRONET_ENVOY) {
            // Cronet is selected, create the cronet engine
            Log.d(TAG, "CREATE CRONET ENGINE FOR " + test)
            createCronetEngine()
        } else {
            // nothing to do?
            Log.d(TAG, "NO SETUP REQUIRED FOR " + test)
            // if direct selected, stop cronet?
        }
    }
}