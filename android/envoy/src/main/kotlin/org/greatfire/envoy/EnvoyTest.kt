package org.greatfire.envoy

import android.net.Uri
import android.net.UrlQuerySanitizer
import android.util.Log

// old shadowsocks
import android.content.Intent
import androidx.core.content.ContextCompat

import IEnvoyProxy.IEnvoyProxy // Go library, we use constants from it here

// This class represents an Envoy connection: type and URL,
// and tracks additional information, such as a URL to any
// proxy used, e.g. Shadowsocks provides a SOCKS5 interface
// which is what our Interceptor actually uses
//
// The url should be the one provided by the caller
// testType lets us know how to treat that URL
// some transports have an additional URL, e.g. a SOCKS5 URL
// for a PT, that gets stored in proxyUrl
// proxyiSEnvoy lets us know if proxyUrl is an Envoy proxy or SOCKS/HTTP

data class EnvoyTest(
    var testType: EnvoyServiceType,
    var url: String,
) {
    companion object {
        private const val TAG = "EnvoyTest"
    }

    // proxy URL for the service providing transport
    // can be SOCKS5, HTTP(S), Envoy, see proxyIsEnvoy
    var proxyUrl: String? = null
    // - true means the proxyUrl refers to a nonstandard Envoy proxy
    // as documented at http://github.com/greatfire/envoy/
    // - false means it's SOCKS or HTTP
    var proxyIsEnvoy: Boolean = false

    // Is this the service we chose to use
    var selectedService: Boolean = false

    // Is the associated service (if any) running?
    var serviceRunning: Boolean = false

    // envoy:// URL related options
    val headers = mutableListOf<Pair<String, String>>()
    // address param creates an resolver rule
    // stash it here in case we can support it with OkHttp
    var address: String? = null
    var resolverRules: String? = null

    // Envoy Global settings and state
    private val state = EnvoyState.getInstance()

    // used to time how long it takes to connect and test
    private var timer: Timer? = null
    // should this be in settings?
    // private var shadowsocks: EnvoyShadowsocks? = null
    private val shadowsocksIntent: Intent? = null

    override fun toString(): String {
        return UrlUtil.sanitizeUrl(url) + " (" + testType + ")"
    }

    override fun toString(): String {
        return UrlUtil.sanitizeUrl(url) + " (" + testType + ")"
    }

    private fun getTimer(): Timer {
        if (timer == null) {
            timer = Timer()
        }
        return timer!!
    }

    // helper to time things
    inner class Timer() {
        private val startTime = System.currentTimeMillis()
        private var stopTime: Long? = null

        fun stop(): Long {
            stopTime = System.currentTimeMillis()
            return stopTime!! - startTime
        }

        fun timeSpent(): Long {
            if (stopTime == null) {
                Log.e(TAG, "timeSpent called before stop()!")
                return 0
            }
            return stopTime!! - startTime
        }
    }

    private fun getV2RayUuid(url: String): String {
        val san = UrlQuerySanitizer()
        san.setAllowUnregisteredParamaters(true)
        san.parseUrl(url)
        return san.getValue("id")
    }

    fun getEnvoyUrl(): String {
        // XXX this needs cleanup? get the Envoy URL from IEP
        state.iep?.let {
            return it.echProxyUrl
        }
        Log.e(TAG, "No EchProxyUrl in IEP")
        return ""
    }

    // returns a string $host:$port where the running service can be found
    suspend fun startService(): String {
        if (serviceRunning) {
            Log.e(TAG, "Tried to start $testType when it was already running")
            return ""
        }

        Log.d(TAG, "starting service for $testType")

        serviceRunning = true

        return when (testType) {
            EnvoyServiceType.HTTP_ECH -> {
                val hostname = Uri.parse(url).getHost()

                // XXX set DOH server for Go code here?
                // or keep it in connect()?

                hostname?.let {
                    val echConfigList = state.dns.getECHConfig(hostname)
                    state.iep?.let {
                        it.setEnvoyUrl(url, echConfigList)
                    }
                }
                return ""
            }
            EnvoyServiceType.HYSTERIA2 -> {
                state.iep?.let {
                    it.hysteria2Server = url
                    it.start(IEnvoyProxy.Hysteria2, "")
                    val addr = it.localAddress(IEnvoyProxy.Hysteria2)
                    EnvoyConnectionTests.isItUpYet(addr)
                    return addr
                }
                return ""
            }
            EnvoyServiceType.SHADOWSOCKS -> {
                // sadly this new code doesn't work, see the comments there
                //
                // shadowsocks = EnvoyShadowsocks(url, state.ctx!!)
                // // come on Kotlin, we just assigned it!
                // shadowsocks!!.start()

                // // block (coroutine friendly) until it's up
                // EnvoyConnectionTests.isItUpYet(
                //     "127.0.0.1", EnvoyShadowsocks.LOCAL_PORT.toInt())

                // return "socks5://127.0.0.1:${EnvoyShadowsocks.LOCAL_PORT}"

                val shadowsocksIntent = Intent(state.ctx!!, ShadowsocksService::class.java)
                shadowsocksIntent.putExtra("org.greatfire.envoy.START_SS_LOCAL", url)
                // XXX shouldn't this be background?
                ContextCompat.startForegroundService(state.ctx!!, shadowsocksIntent)

                EnvoyConnectionTests.isItUpYet(
                    "127.0.0.1", 1080)

                Log.d(TAG, "Oldskool Shadowsocks started")
                return "socks5://127.0.0.1:1080"
            }
            EnvoyServiceType.V2SRTP -> {
                state.iep?.let {
                    val server = Uri.parse(url)

                    it.v2RayServerAddress = server.host
                    it.v2RayServerPort = server.port.toString()
                    it.v2RayId = getV2RayUuid(url)

                    it.start(IEnvoyProxy.V2RaySrtp, "")
                    val addr = it.localAddress(IEnvoyProxy.V2RaySrtp)
                    EnvoyConnectionTests.isItUpYet(addr)
                    return addr
                }
                return ""
            }
            EnvoyServiceType.V2WECHAT -> {
                state.iep?.let {
                    val server = Uri.parse(url)

                    it.v2RayServerAddress = server.host
                    it.v2RayServerPort = server.port.toString()
                    it.v2RayId = getV2RayUuid(url)

                    val host = server.host
                    val port = server.port.toString()
                    val uuid = getV2RayUuid(url)

                    it.start(IEnvoyProxy.V2RayWechat, "")
                    val addr = it.localAddress(IEnvoyProxy.V2RayWechat)
                    EnvoyConnectionTests.isItUpYet(addr)
                    return addr
                }
                return ""
            }
            // XXX there's actually only one service for both
            EnvoyServiceType.CRONET_MASQUE,
            EnvoyServiceType.OKHTTP_MASQUE -> {
                Log.d(TAG, "about to start MASQUE 👺")

                val upstreamUri = Uri.parse(url)
                if (upstreamUri.host == null) {
                    Log.e(TAG, "MASQUE host is null!?")
                    return ""
                }

                state.iep?.let {
                    it.masqueHost = upstreamUri.host

                    var upstreamPort = upstreamUri.port
                    if (upstreamPort == -1) {
                        upstreamPort = 443
                    }
                    it.masquePort = upstreamPort.toLong()

                    it.start(IEnvoyProxy.Masque, "")
                    val addr = it.localAddress(IEnvoyProxy.Masque)
                    return addr
                }
                return ""
            }
            // XXX there's actually only one service for both
            EnvoyServiceType.CRONET_MASQUE,
            EnvoyServiceType.OKHTTP_MASQUE -> {
                Log.d(TAG, "about to start MASQUE 👺")

                val upstreamUri = Uri.parse(url)
                if (upstreamUri.host == null) {
                    Log.e(TAG, "MASQUE host is null!?")
                    return ""
                }

                state.iep?.let {
                    it.masqueHost = upstreamUri.host

                    var upstreamPort = upstreamUri.port
                    if (upstreamPort == -1) {
                        upstreamPort = 443
                    }
                    it.masquePort = upstreamPort.toLong()

                    it.start(IEnvoyProxy.Masque, "")
                    val addr = it.localAddress(IEnvoyProxy.Masque)
                    return addr
                }
                return ""
            }
            else -> {
                Log.e(TAG, "Tried to start an unknown service type $testType")
                return ""
            }
        }
    }

    fun stopService() {
        // stop the associated service
        // this is called to stop unused services
        when (testType) {
            EnvoyServiceType.HYSTERIA2 -> state.iep?.let { it.stop(IEnvoyProxy.Hysteria2) }
            // EnvoyServiceType.SHADOWSOCKS -> shadowsocks?.let { it.stop() }
            // EnvoyServiceType.SHADOWSOCKS -> state.ctx!!.stopService(shadowsocksIntent)
            EnvoyServiceType.V2SRTP -> state.iep?.let { it.stop(IEnvoyProxy.V2RaySrtp) }
            EnvoyServiceType.V2WECHAT -> state.iep?.let { it.stop(IEnvoyProxy.V2RayWechat) }
            EnvoyServiceType.CRONET_MASQUE,
            EnvoyServiceType.OKHTTP_MASQUE -> {
                // this is currently a no-op, but call it in case it ever isn't :)
                state.iep?.let { it.stop(IEnvoyProxy.Masque) }
            }
            EnvoyServiceType.CRONET_ENVOY,
            EnvoyServiceType.OKHTTP_ENVOY,
            EnvoyServiceType.CRONET_PROXY,
            EnvoyServiceType.OKHTTP_PROXY -> {
                // no service for these
                Log.d(TAG, "No service to stop for $testType")
            }
            EnvoyServiceType.HTTP_ECH -> {
                Log.d(TAG, "TODO: we can stop the ECH proxy")
            }
            else -> {
                Log.e(TAG, "Tried to stop an unknown service $testType")
            }
        }
    }

    fun startTimer() {
        getTimer() // this starts the timer as a side effect
    }

    fun stopTimer(): Long {
        return getTimer().stop()
    }

    fun timeSpent(): Long {
        return getTimer().timeSpent()
    }
}