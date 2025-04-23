package org.greatfire.envoy

import android.content.Context
import emissary.Emissary
import org.chromium.net.CronetEngine

class EnvoyNetworkingSettings private constructor() {

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

    // interface with application
    var callback: EnvoyTestCallback? = null

    // can we get away without this?
    // maybe a callback method to return context?
    var ctx: Context? = null

    // Go library
    val emissary = Emissary.newEmissary()

    // DNS related code
    val dns = EnvoyDns()

    companion object {

        @Volatile
        private var instance: EnvoyNetworkingSettings? = null

        fun getInstance() = instance ?: synchronized(this) {
            instance ?: EnvoyNetworkingSettings().also { instance = it }
        }
    }

    fun resetState() {
        // reset state variables for a new set of tests
        envoyEnabled = true
        envoyConnected = false
        useDirect = false
        activeUrl = ""
        activeType = EnvoyServiceType.UNKNOWN
        realActiveUrl = ""
        realActiveType = EnvoyServiceType.UNKNOWN
    }
}