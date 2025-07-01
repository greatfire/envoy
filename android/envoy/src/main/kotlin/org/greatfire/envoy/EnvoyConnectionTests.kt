package org.greatfire.envoy

import android.net.Uri
import android.util.Log
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import kotlinx.coroutines.delay

/*
    Class to hold all the test functions for testing various
    proxy and connection types
*/

class EnvoyConnectionTests {

    companion object {
        private const val TAG = "EnvoyConnectionTests"

        var directTest: DirectTransport? = null;

        // This list of tests persists
        // should this move to the global state/settings
        // object?
        var transports = mutableListOf<Transport>()

        var cronetThreadPool = Executors.newCachedThreadPool()

        // this case is a little complicated, so it has it's own
        // function
        //
        // this URL format is (more or less) documented at
        // https://github.com/greatfire/envoy/tree/master/native
        private fun addEnvoySechemeUrl(url: String) {
            // XXX should this take a Uri as a param?
            val tempUri = Uri.parse(url)
            val realUrl = tempUri.getQueryParameter("url")

            if (realUrl.isNullOrEmpty()) {
                Log.e(TAG, "envoy:// URL missing required `url` parameter")
                return
            }

            // this is the only case where the test.url isn't the
            // caller provided URL.. not sure that matters, but
            // it seems worth calling out the oddity
            // XXX should we preserve the original envoy:// URL?
            //
            // We also can't support all the options (like resolver rules)
            // with OkHttp... should we ignore them and try anyway, or
            // just use Cronet if those features are called for?
            val okTest = OkHttpEnvoyTransport(realUrl)
            val crTest = CronetEnvoyTransport(realUrl)
            val echTest = HttpEchTransport(realUrl)

            // `header_` params
            tempUri.getQueryParameterNames().forEach {
                if (it.startsWith("header_")) {
                    val value = tempUri.getQueryParameter(it)

                    // strip off the "header_" prefix
                    val parts = it.split("_", limit = 2)
                    val name = parts[1]
                    // tag, you're "it" ... witch "it" carefully here
                    Log.d(TAG, "adding global header: $name: $value")
                    value?.let {
                        okTest.headers.add(Pair(name, it))
                        // crTest.headers.add(Pair(name, it))
                        echTest.headers.add(Pair(name, it))
                    }
                }
            }

            // 'resolver' param
            tempUri.getQueryParameter("resolver")?.let {
                // OkHttp is never going to support this?
                okTest.resolverRules = it
                crTest.resolverRules = it
                echTest.resolverRules = it
            }

            // `address` param
            tempUri.getQueryParameter("address")?.let {
                // this is a shortcut for creating a ResolverRule
                // for the `url` param
                val temp = Uri.parse(realUrl)
                val host = temp.getHost()

                val rule = "MAP $host $it"

                // support both `resolver` and `address`
                // the were mutually exclusive in the C++ patches,
                // but they don't need to be
                /*
                if (crTest.resolverRules != null) {
                    crTest.resolverRules += (',' + rule)
                } else {
                    crTest.resolverRules = rule
                }
                */

                // Our OkHttp code doesn't support these, but maybe in the
                // future...
                if (okTest.resolverRules != "") {
                    okTest.resolverRules += (',' + rule)
                } else {
                    okTest.resolverRules = rule
                }
                if (echTest.resolverRules != "") {
                    echTest.resolverRules += (',' + rule)
                } else {
                    echTest.resolverRules = rule
                }

                // currently unused, but stash away the value
                okTest.address = it
                // crTest.address = it
                echTest.address = it
            }

            // 'socks5' param
            // it's poorly named, http(s):// proxies are ok too
            tempUri.getQueryParameter("socks5")?.let {
                okTest.proxyUrl = it
                crTest.proxyUrl = it
                echTest.proxyUrl = it
            }

            with(transports) {
                add(okTest)
                add(crTest)
                add(echTest)
            }
        }

        @JvmStatic
        fun addDirectUrl(url: String) {
            Transport.directUrl = url;
            directTest = DirectTransport(url)
        }

        // and an Envoy proxy URL to the list to test
        //
        // This should probably live somewhere else... it's here because
        // this was the best place to put it at the time :)
        //
        // XXX I'm making up some new schemes here, so we can tell between
        // an HTTPS proxy and an HTTPS Envoy URL (though for the latter
        // we could just require the use for envoy:// urls?)
        @JvmStatic
        fun addEnvoyUrl(url: String) {
            val uri = Uri.parse(url)

            Log.d(TAG, "&&& addEnvoyUrl type: " + uri.getScheme())

            when (uri.getScheme()) {
                "http", "https", "envoy+https" -> {

                    // set the scheme to a real one if needed
                    var tempUrl = url
                    if (uri.scheme == "envoy+https") {
                        tempUrl = url.replaceFirst("""^envoy\+https""".toRegex(), "https")
                    }

                    with(transports) {
                        // XXX should we always test both?
                        add(OkHttpEnvoyTransport(tempUrl))
                        add(CronetEnvoyTransport(tempUrl))
                        add(HttpEchTransport(tempUrl))
                    }
                }

                "masque" -> {
                    with(transports) {
                        add(OkHttpMasqueTransport(url))
                        // add(CronetMasqueTransport(url))
                    }
                }
                // These aren't "officially" supported by Envoy, but they're
                // easy to support
                "socks5", "proxy+http" -> {
                    val tempUrl = when(uri.scheme) {
                        "proxy+https" -> url.replaceFirst(
                            """^proxy\+http""".toRegex(), "http")
                        // OkHttp doesn't yet support HTTPS CONNECT proxies (!)
                        // https://github.com/square/okhttp/issues/8373
                        else -> url
                    }

                    Log.d(TAG, "proxy URL: $tempUrl")

                    with (transports) {
                        add(OkHttpProxyTransport(tempUrl))
                        // add(CronetProxyTransport(tempUrl))
                    }
                }
                "envoy" -> {
                    addEnvoySechemeUrl(url)
                }
                "hysteria2" -> {
                    transports.add(Hysteria2Transport(url))
                }
                "v2srtp" -> {
                    transports.add(V2SrtpTransport(url))
                }
                "v2wechat" -> {
                    transports.add(V2WechatTransport(url))
                }
                "ss" -> {
                    transports.add(ShadowsocksTransport(url))
                }
                else -> {
                    Log.e(TAG, "Unsupported URL: " + url)
                }
            }
        }

        // helper, some services return "host:port"
        suspend fun isItUpYet(addr: String): Boolean {
            val parts = addr.split(":")
            if (parts.size > 1) {
                return isItUpYet(parts[0], parts[1].toInt())
            }
            return false
        }

        // This should live elsewhere
        // poll until a TCP port is listening, so we can use
        // services as soon as they're up
        suspend fun isItUpYet(host: String, port: Int): Boolean {
            // Give up at some point
            val OVERALL_TIMEOUT = 5 * 1000
            // Length between tests
            val POLL_INTERVAL = 1000L

            val startTime = System.currentTimeMillis()

            while (true) {
                // check OVERALL_TIMEOUT
                if (System.currentTimeMillis() - startTime > OVERALL_TIMEOUT) {
                    Log.e(TAG, "Service at $host:$port didn't start in time")
                    return false
                }

                // no timeout, we just want to see if the port is open
                try {
                    val sock = Socket()
                    // this needs some actual time to connect
                    sock.connect(InetSocketAddress(host, port), 1000)
                    Log.d(TAG, "UP! $host:$port")
                    return true
                } catch (e: Exception) {
                    // should be a java.net.ConnectException
                    // should we test that?
                    Log.d(TAG, "Not up yet $host:$port, $e")
                }
                delay(POLL_INTERVAL)
            }

            // this shouldn't be reachable
            return false
        }
    }
}