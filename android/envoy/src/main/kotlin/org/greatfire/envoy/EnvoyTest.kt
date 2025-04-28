package org.greatfire.envoy

import android.net.Uri
import android.net.UrlQuerySanitizer
import android.util.Log

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
    // can be SOCKS5, HTTP(S), Envoy, see proxyiSEnvoy
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
    var address: String? = null
    var resolverRules: String? = null

    // Envoy Global settings and state
    private val settings = EnvoyState.getInstance()

    // used to time how long it takes to connect and test
    private var timer: Timer? = null
    // should this be in settings?
    private var shadowsocks: EnvoyShadowsocks? = null

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
                hostname?.let {
                    val echConfigList = settings.dns.getECHConfig(hostname)
                    settings.emissary.setEnvoyUrl(url, echConfigList)
                }
                return ""
            }
            EnvoyServiceType.HYSTERIA2 -> settings.emissary.startHysteria2(url)
            EnvoyServiceType.SHADOWSOCKS -> {
                shadowsocks = EnvoyShadowsocks(url, settings.ctx!!)
                // come on Kotlin, we just assigned it!
                shadowsocks!!.start()
                return "socks5://127.0.0.1:${EnvoyShadowsocks.LOCAL_PORT}"
            }
            EnvoyServiceType.V2SRTP -> {
                val server = Uri.parse(url)
                val host = server.getHost()
                val port = server.getPort().toString()
                val uuid = getV2RayUuid(url)

                return settings.emissary.startV2RaySrtp(host, port, uuid)
            }
            EnvoyServiceType.V2WECHAT -> {
                val server = Uri.parse(url)
                val host = server.getHost()
                val port = server.getPort().toString()
                val uuid = getV2RayUuid(url)

                return settings.emissary.startV2RayWechat(host, port, uuid)
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
            EnvoyServiceType.HYSTERIA2 -> settings.emissary.stopHysteria2()
            EnvoyServiceType.SHADOWSOCKS -> shadowsocks?.let { it.stop() }
            EnvoyServiceType.V2SRTP -> settings.emissary.stopV2RaySrtp()
            EnvoyServiceType.V2WECHAT -> settings.emissary.stopV2RayWechat()
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