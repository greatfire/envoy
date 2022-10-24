package org.greatfire.envoy

import IEnvoyProxy.IEnvoyProxy
import android.app.IntentService
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.chromium.net.CronetEngine
import org.chromium.net.CronetException
import org.chromium.net.UrlRequest
import org.chromium.net.UrlResponseInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.FileNotFoundException
import java.net.*
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.collections.ArrayList


// IntentService can perform, e.g. ACTION_FETCH_NEW_ITEMS
private const val ACTION_SUBMIT = "org.greatfire.envoy.action.SUBMIT"
private const val ACTION_QUERY = "org.greatfire.envoy.action.QUERY"

private const val EXTRA_PARAM_SUBMIT = "org.greatfire.envoy.extra.PARAM_SUBMIT"
private const val EXTRA_PARAM_DIRECT = "org.greatfire.envoy.extra.PARAM_DIRECT"
private const val EXTRA_PARAM_CERT = "org.greatfire.envoy.extra.PARAM_CERT"
private const val EXTRA_PARAM_CONFIG = "org.greatfire.envoy.extra.PARAM_CONFIG"
private const val EXTRA_PARAM_DNSTT = "org.greatfire.envoy.extra.PARAM_DNSTT"

// Defines a custom Intent action
const val BROADCAST_URL_VALIDATION_SUCCEEDED = "org.greatfire.envoy.VALIDATION_SUCCEEDED"
const val BROADCAST_URL_VALIDATION_FAILED = "org.greatfire.envoy.VALIDATION_FAILED"

// Defines the key for the status "extra" in an Intent
const val EXTENDED_DATA_VALID_URLS = "org.greatfire.envoy.VALID_URLS"
const val EXTENDED_DATA_INVALID_URLS = "org.greatfire.envoy.INVALID_URLS"
const val EXTENDED_DATA_VALID_URL = "org.greatfire.envoy.VALID_URL"
const val EXTENDED_DATA_INVALID_URL = "org.greatfire.envoy.INVALID_URL"
const val EXTENDED_DATA_VALID_SERVICE = "org.greatfire.envoy.VALID_SERVICE"
const val EXTENDED_DATA_INVALID_SERVICE = "org.greatfire.envoy.INVALID_SERVICE"

const val SERVICE_DIRECT = "direct"
const val SERVICE_V2WS = "v2ws"
const val SERVICE_V2SRTP = "v2srtp"
const val SERVICE_V2WECHAT = "v2wechat"
const val SERVICE_HYSTERIA = "hysteria"
const val SERVICE_SS = "ss"
const val SERVICE_HTTPS = "https"

const val PREF_VALID_URLS = "validUrls"
const val LOCAL_URL_BASE = "socks5://127.0.0.1:"

/**
 * An [IntentService] subclass for handling asynchronous task requests in
 * a service on a separate handler thread.

 */
private const val DEFAULT_USER_AGENT = ("Mozilla/5.0 (X11; Linux x86_64) "
        + "AppleWebKit/537.36 (KHTML, like Gecko) "
        + "Chrome/52.0.2743.82 Safari/537.36")

class NetworkIntentService : IntentService("NetworkIntentService") {
    // https://android.googlesource.com/platform/frameworks/base.git/+/oreo-release/services/core/java/com/android/server/connectivity/NetworkMonitor.java
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "NetworkIntentService created")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "NetworkIntentService destroyed")
    }

    private var submittedUrls = Collections.synchronizedList(mutableListOf<String>())
    // currently only a single url is supported for each service but we may support more in the future
    private var v2rayWsUrls = Collections.synchronizedList(mutableListOf<String>())
    private var v2raySrtpUrls = Collections.synchronizedList(mutableListOf<String>())
    private var v2rayWechatUrls = Collections.synchronizedList(mutableListOf<String>())
    private var hysteriaUrls = Collections.synchronizedList(mutableListOf<String>())
    private var shadowsocksUrls = Collections.synchronizedList(mutableListOf<String>())
    private var httpsUrls = Collections.synchronizedList(mutableListOf<String>())

    // the valid url at index 0 should be the one that was selected to use for setting up cronet
    private var validUrls = Collections.synchronizedList(mutableListOf<String>())
    private var invalidUrls = Collections.synchronizedList(mutableListOf<String>())

    // Binder given to clients
    private val binder = NetworkBinder()

    inner class NetworkBinder : Binder() {
        // Return this instance of LocalService so clients can call public methods
        fun getService(): NetworkIntentService = this@NetworkIntentService
    }

    override fun onHandleIntent(intent: Intent?) {
        when (intent?.action) {
            ACTION_SUBMIT -> {
                val urls = intent.getStringArrayListExtra(EXTRA_PARAM_SUBMIT)
                val directUrl = intent.getStringExtra(EXTRA_PARAM_DIRECT)
                val hysteriaCert = intent.getStringExtra(EXTRA_PARAM_CERT)
                val dnsttConfig = intent.getStringArrayListExtra(EXTRA_PARAM_CONFIG)
                val dnsttUrls = intent.getBooleanExtra(EXTRA_PARAM_DNSTT, false)
                handleActionSubmit(urls, directUrl, hysteriaCert, dnsttConfig, dnsttUrls)
            }
            ACTION_QUERY -> {
                handleActionQuery()
            }
        }
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }


    // sorted by latency, from the the fastest one
    fun getValidUrls(): List<String> {
        if (validUrls.isNotEmpty()) {
            return validUrls
        }

        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        val savedValidUrlsStr = sharedPreferences.getString(PREF_VALID_URLS, "[]")
        val savedValidUrls = JSONArray(savedValidUrlsStr)
        // then get it from saved preferences

        val validUrls = ArrayList<String>()
        for (i in 0 until savedValidUrls.length()) {
            validUrls.add(savedValidUrls.getString(i))
        }
        return validUrls
    }

    /**
     * Handle action Submit in the provided background thread with the provided
     * parameters.
     */
    private fun handleActionSubmit(urls: List<String>?,
                                   directUrl: String?,
                                   hysteriaCert: String?,
                                   dnsttConfig: List<String>?,
                                   dnsttUrls: Boolean,
                                   captive_portal_url: String = "https://www.google.com/generate_204") {

        if (dnsttUrls) {
            Log.d(TAG, "got additional dnstt urls, clear previously submitted urls")
            submittedUrls.clear()
        }

        if (directUrl != null) {
            Log.d(TAG, "found direct url: " + directUrl)
            submittedUrls.add(directUrl)
            handleDirectRequest(directUrl, hysteriaCert, dnsttConfig, dnsttUrls)
        }

        urls?.forEach { envoyUrl ->
            submittedUrls.add(envoyUrl)
            if (envoyUrl.startsWith("v2ws://")) {
                Log.d(TAG, "found v2ray url: " + envoyUrl)
                handleV2rayWsSubmit(envoyUrl, captive_portal_url, dnsttConfig, dnsttUrls)
            } else if (envoyUrl.startsWith("v2srtp://")) {
                Log.d(TAG, "found v2ray url: " + envoyUrl)
                handleV2raySrtpSubmit(envoyUrl, captive_portal_url, dnsttConfig, dnsttUrls)
            } else if (envoyUrl.startsWith("v2wechat://")) {
                Log.d(TAG, "found v2ray url: " + envoyUrl)
                handleV2rayWechatSubmit(envoyUrl, captive_portal_url, dnsttConfig, dnsttUrls)
            } else if (envoyUrl.startsWith("hysteria://")) {
                Log.d(TAG, "found hysteria url: " + envoyUrl)
                handleHysteriaSubmit(envoyUrl, captive_portal_url, hysteriaCert, dnsttConfig, dnsttUrls)
            } else if (envoyUrl.startsWith("ss://")) {
                Log.d(TAG, "found ss url: " + envoyUrl)
                handleShadowsocksSubmit(envoyUrl, captive_portal_url, dnsttConfig, dnsttUrls)
            } else {
                Log.d(TAG, "found (https?) url: " + envoyUrl)
                handleHttpsSubmit(envoyUrl, captive_portal_url, dnsttConfig, dnsttUrls)
            }
        }

        if (submittedUrls.isEmpty()) {
            if (dnsttUrls) {
                Log.w(TAG, "no additional dnstt urls submitted, cannot continue")
            } else {
                Log.w(TAG, "no urls submitted, fetch additional urls from dnstt")
                getDnsttUrls(dnsttConfig, hysteriaCert)
            }
        }
    }

    private fun handleHttpsSubmit(url: String, captive_portal_url: String, dnsttConfig: List<String>?, dnsttUrls: Boolean) {

        // nothing to parse at this time, leave url in string format

        httpsUrls.add(url)

        // add a slight delay to give the direct connection a chance
        Log.d(TAG, "submit url after a short delay for testing direct connection")
        ioScope.launch() {
            Log.d(TAG, "start https delay")
            delay(5000L) // wait 5 seconds
            Log.d(TAG, "end https delay")
            handleRequest(url, SERVICE_HTTPS, captive_portal_url, dnsttConfig, dnsttUrls)
        }
    }

    private fun handleShadowsocksSubmit(url: String, captive_portal_url: String, dnsttConfig: List<String>?, dnsttUrls: Boolean) {

        // nothing to parse at this time, leave url in string format

        // start shadowsocks service
        val shadowsocksIntent = Intent(this, ShadowsocksService::class.java)
        shadowsocksIntent.putExtra("org.greatfire.envoy.START_SS_LOCAL", url)
        ContextCompat.startForegroundService(applicationContext, shadowsocksIntent)
        Log.d(TAG, "shadowsocks service started at " + LOCAL_URL_BASE + 1080)

        shadowsocksUrls.add(LOCAL_URL_BASE + 1080)

        // attempt to use fixed delay rather than broadcast receiver
        Log.d(TAG, "submit url after a short delay for starting shadowsocks")
        ioScope.launch() {
            Log.d(TAG, "start shadowsocks delay")
            delay(10000L) // wait 10 seconds
            Log.d(TAG, "end shadowsocks delay")
            handleRequest(LOCAL_URL_BASE + 1080, SERVICE_SS, captive_portal_url, dnsttConfig, dnsttUrls)
        }
    }

    private fun handleHysteriaSubmit(url: String, captive_portal_url: String, hysteriaCert: String?, dnsttConfig: List<String>?, dnsttUrls: Boolean) {
        val uri = URI(url)
        var hystKey = ""
        val rawQuery = uri.rawQuery
        val queries = rawQuery.split("&")
        for (i in 0 until queries.size) {
            val queryParts = queries[i].split("=")
            if (queryParts[0].equals("obfs")) {
                hystKey = queryParts[1]
            }
        }

        // start hysteria service
        if (hystKey.isNullOrEmpty() || hysteriaCert.isNullOrEmpty()) {
            Log.e(TAG, "some arguments required for hysteria service are missing")
        } else {
            val hystCertParts = hysteriaCert.split(",")
            var hystCert = "-----BEGIN CERTIFICATE-----\n"
            for (i in 0 until hystCertParts.size) {
                hystCert = hystCert + hystCertParts[i] + "\n"
            }
            hystCert = hystCert + "-----END CERTIFICATE-----"
            val hysteriaPort = IEnvoyProxy.startHysteria(uri.host + ":" + uri.port, hystKey, hystCert)
            Log.d(TAG, "hysteria service started at " + LOCAL_URL_BASE + hysteriaPort)

            hysteriaUrls.add(LOCAL_URL_BASE + hysteriaPort)

            // method returns port immediately but service is not ready immediately
            Log.d(TAG, "submit url after a short delay for starting hysteria")
            ioScope.launch() {
                Log.d(TAG, "start hysteria delay")
                delay(10000L) // wait 10 seconds
                Log.d(TAG, "end hysteria delay")
                handleRequest(LOCAL_URL_BASE + hysteriaPort, SERVICE_HYSTERIA, captive_portal_url, dnsttConfig, dnsttUrls)
            }
        }
    }

    private fun handleV2rayWsSubmit(url: String, captive_portal_url: String, dnsttConfig: List<String>?, dnsttUrls: Boolean) {

        val uri = URI(url)
        var path = ""
        var id = ""
        val rawQuery = uri.rawQuery
        val queries = rawQuery.split("&")
        for (i in 0 until queries.size) {
            val queryParts = queries[i].split("=")
            if (queryParts[0].equals("path")) {
                path = "/" + queryParts[1]
            } else if (queryParts[0].equals("id")) {
                id = queryParts[1]
            }
        }

        // start v2ray websocket service
        if (path.isNullOrEmpty() || id.isNullOrEmpty()) {
            Log.e(TAG, "some arguments required for v2ray websocket service are missing")
        } else {
            val v2wsPort = IEnvoyProxy.startV2RayWs(uri.host, "" + uri.port, path, id)
            Log.d(TAG, "v2ray websocket service started at " + LOCAL_URL_BASE + v2wsPort)

            v2rayWsUrls.add(LOCAL_URL_BASE + v2wsPort)

            // method returns port immediately but service is not ready immediately
            Log.d(TAG, "submit url after a short delay for starting v2ray websocket")
            ioScope.launch() {
                Log.d(TAG, "start v2ray websocket delay")
                delay(10000L) // wait 10 seconds
                Log.d(TAG, "end v2ray websocket delay")
                handleRequest(LOCAL_URL_BASE + v2wsPort, SERVICE_V2WS, captive_portal_url, dnsttConfig, dnsttUrls)
            }
        }
    }

    private fun handleV2raySrtpSubmit(url: String, captive_portal_url: String, dnsttConfig: List<String>?, dnsttUrls: Boolean) {

        val uri = URI(url)
        var id = ""
        val rawQuery = uri.rawQuery
        val queries = rawQuery.split("&")
        for (i in 0 until queries.size) {
            val queryParts = queries[i].split("=")
            if (queryParts[0].equals("id")) {
                id = queryParts[1]
            }
        }

        // start v2ray srtp service
        if (id.isNullOrEmpty()) {
            Log.e(TAG, "some arguments required for v2ray srtp service are missing")
        } else {
            val v2srtpPort = IEnvoyProxy.startV2raySrtp(uri.host, "" + uri.port, id)
            Log.d(TAG, "v2ray srtp service started at " + LOCAL_URL_BASE + v2srtpPort)

            v2raySrtpUrls.add(LOCAL_URL_BASE + v2srtpPort)

            // method returns port immediately but service is not ready immediately
            Log.d(TAG, "submit url after a short delay for starting v2ray srtp")
            ioScope.launch() {
                Log.d(TAG, "start v2ray srtp delay")
                delay(10000L) // wait 10 seconds
                Log.d(TAG, "end v2ray srtp delay")
                handleRequest(LOCAL_URL_BASE + v2srtpPort, SERVICE_V2SRTP, captive_portal_url, dnsttConfig, dnsttUrls)
            }
        }
    }

    private fun handleV2rayWechatSubmit(url: String, captive_portal_url: String, dnsttConfig: List<String>?, dnsttUrls: Boolean) {

        val uri = URI(url)
        var id = ""
        val rawQuery = uri.rawQuery
        val queries = rawQuery.split("&")
        for (i in 0 until queries.size) {
            val queryParts = queries[i].split("=")
            if (queryParts[0].equals("id")) {
                id = queryParts[1]
            }
        }
        // start v2ray wechat service
        if (id.isNullOrEmpty()) {
            Log.e(TAG, "some arguments required for v2ray wechat service are missing")
        } else {
            val v2wechatPort = IEnvoyProxy.startV2RayWechat(uri.host, "" + uri.port, id)
            Log.d(TAG, "v2ray wechat service started at " + LOCAL_URL_BASE + v2wechatPort)

            v2rayWechatUrls.add(LOCAL_URL_BASE + v2wechatPort)

            // method returns port immediately but service is not ready immediately
            Log.d(TAG, "submit url after a short delay for starting v2ray wechat")
            ioScope.launch() {
                Log.d(TAG, "start v2ray wechat delay")
                delay(10000L) // wait 10 seconds
                Log.d(TAG, "end v2ray wechat delay")
                handleRequest(LOCAL_URL_BASE + v2wechatPort, SERVICE_V2WECHAT, captive_portal_url, dnsttConfig, dnsttUrls)
            }
        }
    }

    // test direct connection to avoid using proxy resources when not required
    private fun handleDirectRequest(directUrl: String, hysteriaCert: String?, dnsttConfig: List<String>?, dnsttUrls: Boolean) {

        Log.d(TAG, "create direct request to " + directUrl)

        val executor: Executor = Executors.newSingleThreadExecutor()
        val myBuilder = CronetEngine.Builder(applicationContext)
        val cronetEngine: CronetEngine = myBuilder
            .setUserAgent(DEFAULT_USER_AGENT).build()
        val requestBuilder = cronetEngine.newUrlRequestBuilder(
            directUrl,
            MyUrlRequestCallback(directUrl, SERVICE_DIRECT, hysteriaCert, dnsttConfig, dnsttUrls),
            executor
        )
        val request: UrlRequest = requestBuilder.build()
        request.start()
    }

    // TODO: do we just hard code captive portal url or add the default here?

    private fun handleRequest(envoyUrl: String, envoyService: String, captive_portal_url: String, dnsttConfig: List<String>?, dnsttUrls: Boolean) {
        handleRequest(envoyUrl, envoyService, captive_portal_url, null, dnsttConfig, dnsttUrls)
    }

    private fun handleRequest(envoyUrl: String, envoyService: String, captive_portal_url: String, hysteriaCert: String?, dnsttConfig: List<String>?, dnsttUrls: Boolean) {

        if (dnsttUrls) {
            Log.d(TAG, "create request to " + captive_portal_url + " for dnstt url: " + envoyUrl)
        } else {
            Log.d(TAG, "create request to " + captive_portal_url + " for url: " + envoyUrl)
        }

        val executor: Executor = Executors.newSingleThreadExecutor()
        val myBuilder = CronetEngine.Builder(applicationContext)
        val cronetEngine: CronetEngine = myBuilder
            .setEnvoyUrl(envoyUrl)
            .setUserAgent(DEFAULT_USER_AGENT).build()
        val requestBuilder = cronetEngine.newUrlRequestBuilder(
            captive_portal_url,
            MyUrlRequestCallback(envoyUrl, envoyService, hysteriaCert, dnsttConfig, dnsttUrls),
            executor
        )
        val request: UrlRequest = requestBuilder.build()
        request.start()
    }

    private fun handleCleanup(envoyUrl: String) {

        Log.d(TAG, envoyUrl + " is redundant or invalid, cleanup and/or stop services if needed")

        if (v2rayWsUrls.contains(envoyUrl)) {
            v2rayWsUrls.remove(envoyUrl)
            if (v2rayWsUrls.isEmpty()) {
                Log.d(TAG, "no v2rayWs urls remaining, stop service")
                IEnvoyProxy.stopV2RayWs()
            } else {
                Log.d(TAG, "" + v2rayWsUrls.size + " v2rayWs urls remaining, service in use")
            }
        } else if (v2raySrtpUrls.contains(envoyUrl)) {
            v2raySrtpUrls.remove(envoyUrl)
            if (v2raySrtpUrls.isEmpty()) {
                Log.d(TAG, "no v2raySrtp urls remaining, stop service")
                IEnvoyProxy.stopV2RaySrtp()
            } else {
                Log.d(TAG, "" + v2raySrtpUrls.size + " v2raySrtp urls remaining, service in use")
            }

        } else if (v2rayWechatUrls.contains(envoyUrl)) {
            v2rayWechatUrls.remove(envoyUrl)
            if (v2rayWechatUrls.isEmpty()) {
                Log.d(TAG, "no v2rayWechat urls remaining, stop service")
                IEnvoyProxy.stopV2RayWechat()
            } else {
                Log.d(TAG, "" + v2rayWechatUrls.size + " v2rayWechat urls remaining, service in use")
            }

        } else if (hysteriaUrls.contains(envoyUrl)) {
            hysteriaUrls.remove(envoyUrl)
            if (hysteriaUrls.isEmpty()) {
                Log.d(TAG, "no hysteria urls remaining, stop service")
                IEnvoyProxy.stopHysteria()
            } else {
                Log.d(TAG, "" + hysteriaUrls.size + " hysteria urls remaining, service in use")
            }

        } else if (shadowsocksUrls.contains(envoyUrl)) {
            shadowsocksUrls.remove(envoyUrl)
            if (shadowsocksUrls.isEmpty()) {
                Log.d(TAG, "no shadowsocks urls remaining, stop service")
                // how to stop shadowsocks service?
            } else {
                Log.d(TAG, "" + shadowsocksUrls.size + " shadowsocks urls remaining, service in use")
            }

        } else if (httpsUrls.contains(envoyUrl)) {
            httpsUrls.remove(envoyUrl)
            if (httpsUrls.isEmpty()) {
                Log.d(TAG, "no https urls remaining (no service)")
            } else {
                Log.d(TAG, "" + httpsUrls.size + " https urls remaining (no service)")
            }
        } else {
            Log.d(TAG, "url was not previously cached")
        }
    }

    fun getDnsttUrls(dnsttConfig: List<String>?, hysteriaCert: String?) {

        // check for dnstt project properties
        if (dnsttConfig.isNullOrEmpty()) {
            Log.e(TAG, "no dnstt parameters were provided, cannot fetch metadata with dnstt")
            return
        } else if (dnsttConfig.size != 5) {
            Log.e(TAG, "the wrong dnstt parameters were provided, cannot fetch metadata with dnstt")
            return
        } else if (dnsttConfig[0].isNullOrEmpty() ||
            dnsttConfig[1].isNullOrEmpty() ||
            dnsttConfig[2].isNullOrEmpty() ||
            (dnsttConfig[3].isNullOrEmpty() && dnsttConfig[4].isNullOrEmpty())) {
            Log.e(TAG, "incorrect dnstt parameters were defined, cannot fetch metadata with dnstt")
            return
        } else {

            // set time limit for dnstt (dnstt allows a long timeout and retries, may never return)
            ioScope.launch() {
                Log.d(TAG, "start dnstt timer")
                dnsttFlag = true
                delay(10000L) // wait 10 seconds
                if (dnsttFlag) {
                    Log.d(TAG, "stop dnstt timer, stop dnstt service")
                    dnsttFlag = false
                    IEnvoyProxy.stopDnstt()
                } else {
                    Log.d(TAG, "dnstt service already stopped")
                }
            }

            /* expected format:
               0. dnstt domain
               1. dnstt key
               2. dnstt path
               3. doh url
               4. dot address
               (either 4 or 5 should be an empty string) */

            try {
                // provide either DOH or DOT address, and provide an empty string for the other
                Log.d(TAG, "start dnstt service: " + dnsttConfig[0] + " / " + dnsttConfig[3] + " / " + dnsttConfig[4] + " / " + dnsttConfig[1])
                val dnsttPort = IEnvoyProxy.startDnstt(
                    dnsttConfig[0],
                    dnsttConfig[3],
                    dnsttConfig[4],
                    dnsttConfig[1]
                )

                Log.d(TAG, "get list of possible urls")
                val url = URL("http://127.0.0.1:" + dnsttPort + dnsttConfig[2])
                Log.d(TAG, "open connection: " + url)
                val connection = url.openConnection() as HttpURLConnection
                try {
                    Log.d(TAG, "set timeout")
                    connection.connectTimeout = 5000
                    Log.d(TAG, "connect")
                    connection.connect()
                } catch (e: SocketTimeoutException) {
                    Log.e(TAG, "connection timeout when connecting: " + e.localizedMessage)
                } catch (e: ConnectException) {
                    Log.e(TAG, "connection error: " + e.localizedMessage)
                } catch (e: Exception) {
                    Log.e(TAG, "unexpected error when connecting: " + e.localizedMessage)
                }

                try {
                    Log.d(TAG, "open input stream")
                    val input = connection.inputStream
                    if (input != null) {
                        Log.d(TAG, "parse json and extract possible urls")
                        val json = input.bufferedReader().use(BufferedReader::readText)
                        val envoyObject = JSONObject(json)
                        val envoyUrlArray = envoyObject.getJSONArray("envoyUrls")

                        var urlList = mutableListOf<String>()

                        for (i in 0 until envoyUrlArray.length()) {
                            if (submittedUrls.contains(envoyUrlArray.getString(i))) {
                                Log.d(TAG, "dnstt url " + envoyUrlArray.getString(i) + " has already been submitted")
                            } else {
                                Log.d(TAG, "dnstt url " + envoyUrlArray.getString(i) + " has not been submitted yet")
                                urlList.add(envoyUrlArray.getString(i))
                            }
                        }

                        if (urlList.size > 0) {
                            Log.d(TAG, "submit " + urlList.size + " additional urls from dnstt")
                            submitDnstt(this@NetworkIntentService, urlList, hysteriaCert)
                        } else {
                            Log.w(TAG, "no additional urls from dnstt to submit")
                        }
                    } else {
                        Log.e(TAG, "response contained no json to parse")
                    }
                } catch (e: SocketTimeoutException) {
                    Log.e(TAG, "connection timeout when getting input: " + e.localizedMessage)
                } catch (e: FileNotFoundException) {
                    Log.e(TAG, "config file error: " + e.localizedMessage)
                } catch (e: Exception) {
                    Log.e(TAG, "unexpected error when reading file: " + e.localizedMessage)
                }
            } catch (e: Error) {
                Log.e(TAG, "dnstt error: " + e.localizedMessage)
            } catch (e: Exception) {
                Log.e(TAG, "unexpected error when starting dnstt: " + e.localizedMessage)
            }

            Log.d(TAG, "stop dnstt service")
            dnsttFlag = false
            IEnvoyProxy.stopDnstt()
        }
    }

    /**
     * Handle action Query in the provided background thread with the provided
     * parameters.
     */
    private fun handleActionQuery() {
        val localIntent = Intent(BROADCAST_URL_VALIDATION_SUCCEEDED).apply {
            // Puts the status into the Intent
            putStringArrayListExtra(EXTENDED_DATA_VALID_URLS, ArrayList(validUrls))
        }
        // Broadcasts the Intent to receivers in this app.
        LocalBroadcastManager.getInstance(this).sendBroadcast(localIntent)
    }

    companion object {
        private const val TAG = "NetworkIntentService"

        val ioScope = CoroutineScope(Dispatchers.IO)
        var dnsttFlag = false

        /**
         * Starts this service to perform action Submit with the given parameters. If
         * the service is already performing a task this action will be queued.
         *
         * @see IntentService
         */
        @JvmStatic
        fun submit(context: Context, urls: List<String>, directUrl: String?, hysteriaCert: String?, dnsttConfig: List<String>?) {
            Log.d(TAG, "jvm submit")
            processSubmit(context, urls, directUrl, hysteriaCert, dnsttConfig, false)
        }

        @JvmStatic
        fun submit(context: Context, urls: List<String>) {
            Log.d(TAG, "backwards compatible submit")
            processSubmit(context, urls, null, null, null, false)
        }

        // no jvm annotation, not for external use
        fun submitDnstt(context: Context, urls: List<String>, hysteriaCert: String?) {
            Log.d(TAG, "dnstt submit")
            processSubmit(context, urls, null, hysteriaCert, null, true)
        }

        // no jvm annotation, not for external use
        fun processSubmit(context: Context, urls: List<String>, directUrl: String?, hysteriaCert: String?, dnsttConfig: List<String>?, dnsttUrls: Boolean) {
            Log.d(TAG, "process submit")
            val intent = Intent(context, NetworkIntentService::class.java).apply {
                action = ACTION_SUBMIT
                putStringArrayListExtra(EXTRA_PARAM_SUBMIT, ArrayList<String>(urls))
                if (directUrl != null) {
                    putExtra(EXTRA_PARAM_DIRECT, directUrl)
                }
                if (hysteriaCert != null) {
                    putExtra(EXTRA_PARAM_CERT, hysteriaCert)
                }
                if (dnsttConfig != null) {
                    putStringArrayListExtra(EXTRA_PARAM_CONFIG,
                        dnsttConfig as java.util.ArrayList<String>?
                    )
                }
                putExtra(EXTRA_PARAM_DNSTT, dnsttUrls)
            }
            context.startService(intent)
        }

        /**
         * Starts this service to perform action Query with the given parameters. If
         * the service is already performing a task this action will be queued.
         *
         * @see IntentService
         */
        @JvmStatic
        fun enqueueQuery(context: Context) {
            val intent = Intent(context, NetworkIntentService::class.java).apply {
                action = ACTION_QUERY
            }
            context.startService(intent)
        }
    }

    inner class MyUrlRequestCallback(private val envoyUrl: String,
                                     private val envoyService: String,
                                     private val hysteriaCert: String?,
                                     private val dnsttConfig: List<String>?,
                                     private val dnsttUrls: Boolean) : UrlRequest.Callback() {

        override fun onRedirectReceived(
                request: UrlRequest?,
                info: UrlResponseInfo?,
                newLocationUrl: String?
        ) {
            Log.i(TAG, "onRedirectReceived method called.")
            // You should call the request.followRedirect() method to continue
            // processing the request.
            request?.followRedirect()
        }

        override fun onResponseStarted(request: UrlRequest?, info: UrlResponseInfo?) {
            Log.i(TAG, "onResponseStarted method called.")
            // You should call the request.read() method before the request can be
            // further processed. The following instruction provides a ByteBuffer object
            // with a capacity of 102400 bytes to the read() method.
            request?.read(ByteBuffer.allocateDirect(102400))
        }

        override fun onReadCompleted(
                request: UrlRequest?,
                info: UrlResponseInfo?,
                byteBuffer: ByteBuffer?
        ) {
            Log.i(TAG, "onReadCompleted method called.")
            // You should keep reading the request until there's no more data.
            request?.read(ByteBuffer.allocateDirect(102400))
        }

        // TODO: do we continue to return all urls or can we start cronet here?
        override fun onSucceeded(request: UrlRequest?, info: UrlResponseInfo?) {
            if (info != null) {

                if (this@NetworkIntentService.validUrls.size > 0) {
                    Log.d(TAG, "got redundant url: " + envoyUrl)
                    handleCleanup(envoyUrl)
                }

                // only a 200 status code is valid, otherwise return invalid url as in onFailed
                if (info.httpStatusCode in 200..299) {
                    // logs captive portal url used to validate envoy url
                    Log.d(TAG, "onSucceeded method called for " + info.url + " / " + envoyService + " -> got " + info.httpStatusCode + " response code so tested url is valid")
                    this@NetworkIntentService.validUrls.add(envoyUrl)

                    // store valid urls in preferences
                    val sharedPreferences =
                        PreferenceManager.getDefaultSharedPreferences(this@NetworkIntentService)
                    val editor: SharedPreferences.Editor = sharedPreferences.edit()
                    val json = JSONArray(this@NetworkIntentService.validUrls)
                    editor.putString(PREF_VALID_URLS, json.toString())
                    editor.apply()

                    val localIntent = Intent(BROADCAST_URL_VALIDATION_SUCCEEDED).apply {
                        // puts the validation status into the intent
                        putStringArrayListExtra(EXTENDED_DATA_VALID_URLS, ArrayList(validUrls))
                        putExtra(EXTENDED_DATA_VALID_URL, envoyUrl)
                        putExtra(EXTENDED_DATA_VALID_SERVICE, envoyService)
                    }
                    LocalBroadcastManager.getInstance(this@NetworkIntentService).sendBroadcast(localIntent)
                } else {
                    // logs captive portal url used to validate envoy url
                    Log.e(TAG, "onSucceeded method called for " + info.url + " / " + envoyService + " -> got " + info.httpStatusCode + " response code so tested url is invalid")
                    handleInvalidUrl()
                }
            } else {
                Log.w(TAG, "onSucceeded method called but UrlResponseInfo was null")
            }
        }

        override fun onFailed(
                request: UrlRequest?,
                info: UrlResponseInfo?,
                error: CronetException?
        ) {
            // logs captive portal url used to validate envoy url
            Log.e(TAG, "onFailed method called for invalid url " + info?.url + " / " + envoyService + " -> " + error?.message)
            handleInvalidUrl()
        }

        fun handleInvalidUrl() {
            handleCleanup(envoyUrl)

            // broadcast intent with invalid urls so application can handle errors
            this@NetworkIntentService.invalidUrls.add(envoyUrl)
            val localIntent = Intent(BROADCAST_URL_VALIDATION_FAILED).apply {
                // puts the validation status into the intent
                putStringArrayListExtra(EXTENDED_DATA_INVALID_URLS, ArrayList(invalidUrls))
                putExtra(EXTENDED_DATA_INVALID_URL, envoyUrl)
                putExtra(EXTENDED_DATA_INVALID_SERVICE, envoyService)
            }
            LocalBroadcastManager.getInstance(this@NetworkIntentService).sendBroadcast(localIntent)

            // TODO: should we broadcast an indication that we are trying to fetch urls from dnstt?
            // check whether all submitted urls have failed
            if (submittedUrls.size > 0 && invalidUrls.size > 0 && submittedUrls.size == invalidUrls.size) {
                if (dnsttUrls) {
                    Log.w(TAG, "all additional dnstt urls submitted have failed, cannot continue")
                } else if (dnsttConfig.isNullOrEmpty()) {
                    Log.w(TAG, "no dnstt config was found, cannot continue")
                } else {
                    Log.w(TAG, "all urls submitted have failed, fetch additional urls from dnstt")
                    getDnsttUrls(dnsttConfig, hysteriaCert)
                }
            } else {
                Log.d(TAG, "" + submittedUrls.size + " were submitted, " + invalidUrls.size + " have failed")
            }
        }
    }
}



