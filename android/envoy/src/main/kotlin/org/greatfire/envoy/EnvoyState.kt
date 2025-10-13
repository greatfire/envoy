package org.greatfire.envoy

import org.greatfire.envoy.transport.Transport

import android.content.Context
import android.util.Log
import IEnvoyProxy.IEnvoyProxy // Go library
import java.io.File
import java.security.PrivateKey
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
    var activeServiceType = AtomicInteger(EnvoyTransportType.UNKNOWN.ordinal)
    var activeService: Transport? = null
    val additionalWorkingConnections = mutableListOf<Transport>()


    // if set, wait an increasing amount of time before retrying blocked urls
    var backoffEnabled = false

    // our Cronet engine
    var cronetEngine: CronetEngine? = null
    var cronetCache: File? = null

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

    // for debugging, simulate a connection timeout
    var debugTimeoutDriect = false

    // when set, all urls will be tested
    var testAllUrls = false

    // HTTP Concealed Auth config
    var concealedAuthPrivateKey: PrivateKey? = null
    var concealedAuthPublicKey: ByteArray? = null
    var concealedAuthUser: String? = ""

    private fun createCronetEngine(transport: Transport) {
        // I think we can reuse the cache dir between runs?
        // XXX we used to have multiple tests cronet based tests
        // running in parallel...

        // should this live somewhere else?
        cronetCache = File(ctx!!.cacheDir, "cronet-cache")
        cronetCache?.let{
            if (!it.exists()) {
                it.mkdirs()
            }
        }

        cronetEngine = CronetNetworking.buildEngine(
            context = ctx!!,
            cacheFolder = cronetCache!!.absolutePath,
            proxyUrl = transport.proxyUrl,
            resolverRules = transport.resolverRules,
            cacheSize = 10, // cache size in MB
        )
    }

    fun cleanupCronetCache() {
        cronetCache?.let{
            if (it.exists()) {
                it.deleteRecursively()
            }
        }
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
    fun connectIfNeeded(transport: Transport) {
        // Check if we already have a working connection before continuing
        // set that we do otherwise
        if (connected.compareAndSet(false, true)) {
            Log.i(TAG, "🚀🚀🚀 Envoy connected")

            // start the cronet engine if we're using a cronet service
            when (transport.testType) {
                EnvoyTransportType.CRONET_ENVOY,
                EnvoyTransportType.CRONET_PROXY,
                EnvoyTransportType.CRONET_MASQUE, -> {
                    createCronetEngine(transport)
                }
                else -> "" // nothing to do
            }

            activeServiceType.set(transport.testType.ordinal)
            activeService = transport
            transport.selectedService = true

            Log.d(TAG, "🍍 activeService is $activeService")

        // this can trigger twice if 2 threads trigger the "direct detection"
        // in the interceptor at about the same time, so don't try to replace
        // the direct connection with a direct connection
        // XXX this is the only use of activeServiceType
        } else if(activeServiceType.get() != EnvoyTransportType.DIRECT.ordinal
                  && transport.testType == EnvoyTransportType.DIRECT) {
            Log.i(TAG, "👉 DIRECT overriding previous connection")

            val previousService = activeService

            // activate the direct connection
            activeServiceType.set(transport.testType.ordinal)
            activeService = transport
            transport.selectedService = true

            previousService?.let {
                it.selectedService = false
                it.stopService()
            }
        } else {
            // this one worked, but we already selected a service
            // TODO use these later?
            Log.d(TAG, "💤 additional working service $transport")
            additionalWorkingConnections.add(transport)
        }
    }
}