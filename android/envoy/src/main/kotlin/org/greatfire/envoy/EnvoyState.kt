package org.greatfire.envoy

import android.content.Context
import android.util.Log
import IEnvoyProxy.IEnvoyProxy // Go library
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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

    // moving this back to state because it's state
    var connected = AtomicBoolean(false)
    var activeServiceType = AtomicInteger(EnvoyServiceType.UNKNOWN.ordinal)
    var activeService: EnvoyTest? = null
    val additionalWorkingConnections = mutableListOf<EnvoyTest>()


    // if set, wait an increasing amount of time before retrying blocked urls
    var backoffEnabled = false

    // our Cronet engine
    var cronetEngine: CronetEngine? = null

    // interface with application
    var callback: EnvoyTestCallback? = null

    // can we get away without this?
    // maybe a callback method to return context?
    var ctx: Context? = null

    // Go library
    var iep: IEnvoyProxy.Controller? = null

    // DNS related code
    val dns = EnvoyDns()

    val shadowsocks: ShadowsocksService? = null

    private fun createCronetEngine(test: EnvoyTest) {
        // I think we can reuse the cache dir between runs?
        // XXX we used to have multiple tests cronet based tests
        // running in parallel...

        // should this live somewhere else?
        val cacheDir = File(ctx!!.cacheDir, "cronet-cache")
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }

        cronetEngine = CronetNetworking.buildEngine(
            context = ctx!!,
            cacheFolder = cacheDir.absolutePath,
            proxyUrl = test.proxyUrl,
            resolverRules = test.resolverRules,
            cacheSize = 10, // cache size in MB
        )
    }

    fun InitIEnvoyProxy() {
        ctx?.let {
            val ptState = File(it.cacheDir, "pt-state")
            if(!ptState.exists()) {
                ptState.mkdirs()
            }

            val enableLogging = true
            val unsafeLogging = false
            val logLevel = "DEBUG"

            iep = IEnvoyProxy.newController(
                ptState.toString(), enableLogging, unsafeLogging, logLevel, null)
        }
    }

    // called when the connection worker found a successful connection
    fun connectIfNeeded(test: EnvoyTest) {
        // Check if we already have a working connection before continuing
        // set that we do otherwise
        if (connected.compareAndSet(false, true)) {
            Log.i(TAG, "🚀🚀🚀 Envoy connected")

            // start the cronet engine if we're using a cronet service
            when (test.testType) {
                EnvoyServiceType.CRONET_ENVOY,
                EnvoyServiceType.CRONET_PROXY,
                EnvoyServiceType.CRONET_MASQUE, -> {
                    createCronetEngine(test)
                }
                else -> "" // nothing to do
            }

            activeServiceType.set(test.testType.ordinal)
            activeService = test
            test.selectedService = true

            Log.d(TAG, "🍍 activeService is $activeService")

        } else if(test.testType == EnvoyServiceType.DIRECT) {
            Log.i(TAG, "👉 DIRECT overriding previous connection")

            val previousService = activeService

            // activate the direct connection
            activeServiceType.set(test.testType.ordinal)
            activeService = test
            test.selectedService = true

            previousService?.let {
                it.selectedService = false
                it.stopService()
            }
        } else {
            // this one worked, but we already selected a service
            // TODO use these later?
            Log.d(TAG, "💤 additional working service $test")
            additionalWorkingConnections.add(test)
        }
    }
}