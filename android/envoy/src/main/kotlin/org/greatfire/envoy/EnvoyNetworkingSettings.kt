package org.greatfire.envoy

import android.content.Context
import android.util.Log
import emissary.Emissary // Envoy Go library
import java.io.File
import kotlinx.coroutines.sync.*
import org.chromium.net.CronetEngine

class EnvoyNetworkingSettings private constructor() {
    // Settings
    //
    // How many coroutines to use to test URLs
    // this effectively limits the number of requests we can make at once
    // while testing
    var concurrency = 6 // XXX

    // are we connected
    var envoyConnected = false
    // if true, the interceptor passes requets through
    var useDirect = false
    // active Envoy or Proxy URL
    var activeConnection: EnvoyTest? = null
    val additionalWorkingConnections = mutableListOf<EnvoyTest>()

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

    companion object {
        private const val TAG = "EnvoyNetworkingSettings"

        // Singleton
        @Volatile
        private var instance: EnvoyNetworkingSettings? = null

        fun getInstance() = instance ?: synchronized(this) {
            instance ?: EnvoyNetworkingSettings().also { instance = it }
        }

        @Volatile
        private var connectedMutex = Mutex()
    }

    fun resetState() {
        // reset state variables for a new set of tests
        envoyConnected = false
        useDirect = false
        activeConnection = null
        // XXX should we maybe use this here?
        additionalWorkingConnections.clear()
    }

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

    // The connection worker found a successful connection
    suspend fun connected(test: EnvoyTest) {
        connectedMutex.withLock {
            // if we're already connected, ignore this UNLESS
            // it's telling us to use DIRECT
            if (envoyConnected && test.testType != EnvoyServiceType.DIRECT) {
                Log.d(TAG, "Already connected, later success $test")
                // We're just saving these for now, maybe we can use them
                // if either the main connection fails or we want to cycle
                // connections
                additionalWorkingConnections.add(test)
                return
            }

            Log.i(TAG, "🎉 Envoy Connected!")
            Log.d(TAG, "connected: $test")

            when (test.testType) {
                EnvoyServiceType.DIRECT -> {
                    // everything else is ignored if this is true
                    useDirect = true
                }
                EnvoyServiceType.CRONET_ENVOY -> {
                    // Cronet is selected, create the cronet engine
                    createCronetEngine()
                }
                else -> return // nothing to do
            }

            test.selectedService = true
            activeConnection = test
            // this setting makes the Interceptor change behavior,
            // so flip it last. XXX should this be more thread safe?
            envoyConnected = true // 🎉
        }
    }
}