package org.greatfire.envoy

import android.content.Context
import android.util.Log
import emissary.Emissary // Envoy Go library
import java.io.File
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
    // XXX in some cases we need to use both a proxy and an Envoy URL
    var activeUrl: String = ""
    // this value is essentially meaningless before we try connecting
    var activeType = EnvoyServiceType.UNKNOWN

    // XXX these are dumb names, but currently activeType needs to be a
    // generic SOCKS5 proxy for most protocols, so store the "real" one
    // here for now
    var realActiveType = EnvoyServiceType.UNKNOWN
    var realActiveUrl: String = ""

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
    }

    fun resetState() {
        // reset state variables for a new set of tests
        envoyConnected = false
        useDirect = false
        activeUrl = ""
        activeType = EnvoyServiceType.UNKNOWN
        realActiveUrl = ""
        realActiveType = EnvoyServiceType.UNKNOWN
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