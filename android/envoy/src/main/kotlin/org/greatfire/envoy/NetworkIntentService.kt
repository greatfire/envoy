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
import java.io.File
import java.io.FileNotFoundException
import java.net.*
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.collections.ArrayList


// IntentService can perform, e.g. ACTION_FETCH_NEW_ITEMS
private const val ACTION_SUBMIT = "org.greatfire.envoy.action.SUBMIT"

private const val EXTRA_PARAM_SUBMIT = "org.greatfire.envoy.extra.PARAM_SUBMIT"
private const val EXTRA_PARAM_DIRECT = "org.greatfire.envoy.extra.PARAM_DIRECT"
private const val EXTRA_PARAM_CERT = "org.greatfire.envoy.extra.PARAM_CERT"
private const val EXTRA_PARAM_SOURCES = "org.greatfire.envoy.extra.PARAM_SOURCES"
private const val EXTRA_PARAM_INTERVAL = "org.greatfire.envoy.extra.PARAM_INTERVAL"
private const val EXTRA_PARAM_START = "org.greatfire.envoy.extra.PARAM_START"
private const val EXTRA_PARAM_END = "org.greatfire.envoy.extra.PARAM_END"
private const val EXTRA_PARAM_FIRST = "org.greatfire.envoy.extra.PARAM_FIRST"

// Defines a custom Intent action
const val ENVOY_BROADCAST_VALIDATION_SUCCEEDED = "org.greatfire.envoy.VALIDATION_SUCCEEDED"
const val ENVOY_BROADCAST_VALIDATION_FAILED = "org.greatfire.envoy.VALIDATION_FAILED"
const val ENVOY_BROADCAST_BATCH_SUCCEEDED = "org.greatfire.envoy.BATCH_SUCCEEDED"
const val ENVOY_BROADCAST_BATCH_FAILED = "org.greatfire.envoy.BATCH_FAILED"
const val ENVOY_BROADCAST_UPDATE_SUCCEEDED = "org.greatfire.envoy.UPDATE_SUCCEEDED"
const val ENVOY_BROADCAST_UPDATE_FAILED = "org.greatfire.envoy.UPDATE_FAILED"
const val ENVOY_BROADCAST_VALIDATION_CONTINUED = "org.greatfire.envoy.VALIDATION_CONTINUED"
const val ENVOY_BROADCAST_VALIDATION_ENDED = "org.greatfire.envoy.VALIDATION_ENDED"

// Defines the key for the status "extra" in an Intent
const val ENVOY_DATA_URL_SUCCEEDED = "org.greatfire.envoy.URL_SUCCEEDED"
const val ENVOY_DATA_URL_FAILED = "org.greatfire.envoy.URL_FAILED"
const val ENVOY_DATA_SERVICE_SUCCEEDED = "org.greatfire.envoy.SERVICE_SUCCEEDED"
const val ENVOY_DATA_SERVICE_FAILED = "org.greatfire.envoy.SERVICE_FAILED"
const val ENVOY_DATA_URL_LIST = "org.greatfire.envoy.URL_LIST"
const val ENVOY_DATA_SERVICE_LIST = "org.greatfire.envoy.SERVICE_LIST"
const val ENVOY_DATA_UPDATE_URL = "org.greatfire.envoy.UPDATE_URL"
const val ENVOY_DATA_UPDATE_LIST = "org.greatfire.envoy.UPDATE_LIST"
const val ENVOY_DATA_VALIDATION_MS = "org.greatfire.envoy.VALIDATION_MS"
const val ENVOY_DATA_VALIDATION_ENDED_CAUSE = "org.greatfire.envoy.VALIDATION_ENDED_CAUSE"

const val ENVOY_SERVICE_DIRECT = "direct"
const val ENVVY_SERVICE_SNOWFLAKE = "snowflake"
const val ENVOY_SERVICE_V2WS = "v2ws"
const val ENVOY_SERVICE_V2SRTP = "v2srtp"
const val ENVOY_SERVICE_V2WECHAT = "v2wechat"
const val ENVOY_SERVICE_HYSTERIA = "hysteria"
const val ENVOY_SERVICE_SS = "ss"
const val ENVOY_SERVICE_HTTPS = "https"
const val ENVOY_ENDED_EMPTY = "empty"
const val ENVOY_ENDED_BLOCKED = "blocked"
const val ENVOY_ENDED_FAILED = "failed"
const val ENVOY_ENDED_TIMEOUT = "timeout"
const val ENVOY_ENDED_UNKNOWN = "unknown"

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
    private var shuffledUrls = Collections.synchronizedList(mutableListOf<String>())
    private var shuffledHttps = Collections.synchronizedList(mutableListOf<String>())
    private var currentBatch = Collections.synchronizedList(mutableListOf<String>())
    private var currentBatchChecked = Collections.synchronizedList(mutableListOf<String>())
    private var currentServiceChecked = Collections.synchronizedList(mutableListOf<String>())
    private var batchInProgress = false
    private var validationStart = 0L
    private var additionalUrlSources = Collections.synchronizedList(mutableListOf<String>())

    // currently only a single url is supported for each service but we may support more in the future
    private var v2rayWsUrls = Collections.synchronizedList(mutableListOf<String>())
    private var v2raySrtpUrls = Collections.synchronizedList(mutableListOf<String>())
    private var v2rayWechatUrls = Collections.synchronizedList(mutableListOf<String>())
    private var hysteriaUrls = Collections.synchronizedList(mutableListOf<String>())
    private var shadowsocksUrls = Collections.synchronizedList(mutableListOf<String>())
    private var snowflakeUrls = Collections.synchronizedList(mutableListOf<String>())
    private var httpsUrls = Collections.synchronizedList(mutableListOf<String>())
    private var additionalUrls = Collections.synchronizedList(mutableListOf<String>())

    // the valid url at index 0 should be the one that was selected to use for setting up cronet
    private var validUrls = Collections.synchronizedList(mutableListOf<String>())
    private var invalidUrls = Collections.synchronizedList(mutableListOf<String>())
    private var blockedUrls = Collections.synchronizedList(mutableListOf<String>())

    // Binder given to clients
    private val binder = NetworkBinder()

    // map urls to their cache and engine for cleanup
    private var cacheCounter = 1
    private val cacheMap = Collections.synchronizedMap(mutableMapOf<String, String>())
    private val cronetMap = Collections.synchronizedMap(mutableMapOf<String, CronetEngine>())

    private val httpPrefixes = Collections.synchronizedList(mutableListOf<String>("https", "http", "envoy"))
    private val supportedPrefixes = Collections.synchronizedList(mutableListOf<String>("v2ws", "v2srtp", "v2wechat", "hysteria", "ss", "snowflake"))

    fun checkValidationTime(): Boolean {
        val validationCheck = System.currentTimeMillis()
        if ((validationCheck - validationStart) < TIME_LIMIT) {
            Log.d(TAG, "validation checked at " + validationCheck + ", time limit not yet exceeded: " + (validationCheck - validationStart))
            return true
        } else {
            Log.d(TAG, "validation checked at " + validationCheck + ", time limit has been exceeded: " + (validationCheck - validationStart))
            return false
        }
    }

    fun calculateValidationTime(): Long {
        val validationStop = System.currentTimeMillis()
        Log.d(TAG, "validation calculated at " + validationStop + ", duration: " + (validationStop - validationStart))
        return validationStop - validationStart
    }

    private fun getCauseOfFailure(): String {
        if (!checkValidationTime()
            && (((shuffledUrls.size + shuffledHttps.size) > 0)) || (additionalUrls.size > 0)) {
            // the cause of failure is reported as a timeout only if there were remaining unchecked urls
            return ENVOY_ENDED_TIMEOUT
        } else if (submittedUrls.isNullOrEmpty()) {
            // check whether no urls were submitted because they had all been previously blocked
            if (blockedUrls.isNullOrEmpty()) {
                return ENVOY_ENDED_EMPTY
            } else {
                return ENVOY_ENDED_BLOCKED
            }
        } else if (!invalidUrls.isNullOrEmpty()) {
            return ENVOY_ENDED_FAILED
        } else {
            return ENVOY_ENDED_UNKNOWN
        }
    }

    private fun broadcastValidationFailure() {
        val localIntent = Intent(ENVOY_BROADCAST_VALIDATION_ENDED)
        localIntent.putExtra(ENVOY_DATA_VALIDATION_MS, calculateValidationTime())
        localIntent.putExtra(ENVOY_DATA_VALIDATION_ENDED_CAUSE, getCauseOfFailure())
        LocalBroadcastManager.getInstance(this@NetworkIntentService).sendBroadcast(localIntent)
    }

    private fun broadcastUpdateFailed(url: String) {
        Log.e(TAG, "broadcast failure for url: " + url)
        val localIntent = Intent(ENVOY_BROADCAST_UPDATE_FAILED).apply {
            putExtra(ENVOY_DATA_UPDATE_URL, url)
        }
        LocalBroadcastManager.getInstance(this@NetworkIntentService).sendBroadcast(localIntent)
    }

    private fun broadcastUpdateSucceeded(url: String, list: List<String>) {
        Log.d(TAG, "broadcast success for url: " + url)
        val localIntent = Intent(ENVOY_BROADCAST_UPDATE_SUCCEEDED).apply {
            putExtra(ENVOY_DATA_UPDATE_URL, url)
            putStringArrayListExtra(ENVOY_DATA_UPDATE_LIST, ArrayList(list))
        }
        LocalBroadcastManager.getInstance(this@NetworkIntentService).sendBroadcast(localIntent)
    }

    private fun broadcastBatchStatus(status: String) {
        // create local copies to sort and avoid possible concurrent modification exception
        val localBatchList = ArrayList<String>(currentBatchChecked)
        val localServiceList = ArrayList<String>(currentServiceChecked)
        Collections.sort(localBatchList)
        Collections.sort(localServiceList)

        val localIntent = Intent(status).apply {
            putStringArrayListExtra(ENVOY_DATA_URL_LIST, localBatchList)
            putStringArrayListExtra(ENVOY_DATA_SERVICE_LIST, localServiceList)
        }
        LocalBroadcastManager.getInstance(this@NetworkIntentService).sendBroadcast(localIntent)
    }

    inner class NetworkBinder : Binder() {
        // Return this instance of LocalService so clients can call public methods
        fun getService(): NetworkIntentService = this@NetworkIntentService
    }

    override fun onHandleIntent(intent: Intent?) {
        when (intent?.action) {
            ACTION_SUBMIT -> {
                val urls = intent.getStringArrayListExtra(EXTRA_PARAM_SUBMIT)
                val directUrls = intent.getStringArrayListExtra(EXTRA_PARAM_DIRECT)
                val hysteriaCert = intent.getStringExtra(EXTRA_PARAM_CERT)
                val urlSources = intent.getStringArrayListExtra(EXTRA_PARAM_SOURCES)
                val urlInterval = intent.getIntExtra(EXTRA_PARAM_INTERVAL, 1)
                val urlStart = intent.getIntExtra(EXTRA_PARAM_START, -1)
                val urlEnd = intent.getIntExtra(EXTRA_PARAM_END, -1)
                val firstAttempt = intent.getBooleanExtra(EXTRA_PARAM_FIRST, false)
                handleActionSubmit(urls, directUrls, hysteriaCert, urlSources, urlInterval, urlStart, urlEnd, firstAttempt)
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
    private fun handleActionSubmit(
        urls: List<String>?,
        directUrls: List<String>?,
        hysteriaCert: String?,
        urlSources: List<String>?,
        urlInterval: Int,
        urlStart: Int,
        urlEnd: Int,
        firstAttempt: Boolean
    ) {

        if (firstAttempt) {
            validationStart = System.currentTimeMillis()
            Log.d(TAG, "validation started at " + validationStart)

            Log.d(TAG, "clear " + additionalUrlSources.size + " previously submitted url sources")
            additionalUrlSources.clear()

            Log.d(TAG, "clear " + additionalUrls.size + " previously submitted additional urls")
            additionalUrls.clear()

            if (!urlSources.isNullOrEmpty()) {
                urlSources.forEach { urlSource ->
                    Log.d(TAG, "found url source: " + urlSource)
                    additionalUrlSources.add(urlSource)
                }

                // fetch additional urls at startup to collect analytics
                getAdditionalUrls(urlInterval, urlStart, urlEnd)
            }
        } else {
            Log.d(TAG, "additional urls have been submitted, clear " + additionalUrls.size + " cached urls")
            additionalUrls.clear()
        }

        Log.d(TAG, "clear " + submittedUrls.size + " previously submitted urls")
        submittedUrls.clear()

        Log.d(TAG, "clear " + blockedUrls.size + " previously blocked urls")
        blockedUrls.clear()

        if (!directUrls.isNullOrEmpty()) {
            directUrls.forEach { directUrl ->
                Log.d(TAG, "found direct url: " + directUrl)
                submittedUrls.add(directUrl)
                handleDirectRequest(directUrl, hysteriaCert)
            }
        }

        val urlsToSubmit = mutableListOf<String>()
        if (!urls.isNullOrEmpty()) {
            urls.forEach { url ->
                if (shouldSubmitUrl(url)) {
                    urlsToSubmit.add(url)
                } else {
                    blockedUrls.add(url)
                }
            }
        }

        if (urlsToSubmit.isNullOrEmpty()) {
            if (firstAttempt && !additionalUrls.isNullOrEmpty()) {
                // if first attempt and additional urls available, submit additional urls
                submitAdditionalUrls(hysteriaCert)
            } else {
                Log.w(TAG, "no urls found to submit, cannot continue")
                broadcastValidationFailure()
            }
        } else {
            urlsToSubmit.forEach() { url ->
                val parts = url.split(":")
                val prefix = parts[0]
                if (url.contains("test")) {
                    // no-op
                } else if (httpPrefixes.contains(prefix)) {
                    shuffledHttps.add(url)
                } else if (supportedPrefixes.contains(prefix)) {
                    shuffledUrls.add(url)
                } else {
                    Log.w(TAG, "found url with unsupported prefix: " + prefix)
                }
            }
            Log.d(TAG, "shuffle " + (shuffledHttps.size + shuffledUrls.size) + " submitted urls")
            Collections.shuffle(shuffledHttps)
            Collections.shuffle(shuffledUrls)
            handleBatch(hysteriaCert)
        }
    }

    private fun shouldSubmitUrl(url: String): Boolean {

        val currentTime = System.currentTimeMillis()
        val preferences = PreferenceManager.getDefaultSharedPreferences(this)
        val failureTime = preferences.getLong(url + TIME_SUFFIX, 0)
        val failureCount = preferences.getInt(url + COUNT_SUFFIX, 0)

        if ((failureCount in 1..3 && currentTime - failureTime < ONE_HOUR_MS * failureCount)
            || (failureCount == 4 && currentTime - failureTime < ONE_DAY_MS)
            || (failureCount >= 5 && currentTime - failureTime < ONE_WEEK_MS)) {
            Log.d(TAG, "time limit has not expired for url(" + failureTime + "), do not submit: " + url)
            return false
        } else {
            Log.d(TAG, "time limit expired for url(" + failureTime + "), submit again: " + url)
            return true
        }
    }

    private fun handleBatch(hysteriaCert: String?,
                            captive_portal_url: String = "https://www.google.com/generate_204") {

        // under certain circumstances this can be called multiple times when a single batch is completed
        if (batchInProgress) {
            Log.d(TAG, "batch already in progress")
            return
        } else {
            batchInProgress = true
            Log.d(TAG, "start new batch")
        }

        var max = shuffledUrls.size + shuffledHttps.size
        if (max > 3) {
            max = 3
        }

        currentBatch.clear()
        currentBatchChecked.clear()
        currentServiceChecked.clear()

        var alternativeIncluded = false

        repeat(max) { index ->
            if (shuffledUrls.isNullOrEmpty() || (alternativeIncluded && !shuffledHttps.isNullOrEmpty())) {
                Log.d(TAG, "add " + (index + 1) + " http url out of " + max + " to batch: " + shuffledHttps[0])
                currentBatch.add(shuffledHttps[0])
                shuffledHttps.removeAt(0)
            } else {
                Log.d(TAG, "add " + (index + 1) + " non-http url out of " + max + " to batch: " + shuffledUrls[0])
                currentBatch.add(shuffledUrls[0])
                shuffledUrls.removeAt(0)
                alternativeIncluded = true
            }
        }

        Collections.shuffle(currentBatch)
        currentBatch.forEach { envoyUrl ->
            submittedUrls.add(envoyUrl)

            // generate cache directories here where it's synchronized
            // increment counter after allocating so we don't retry numbers
            var cacheDir: File
            do {
                cacheDir = File(applicationContext.cacheDir, "cache_" + cacheCounter)
                Log.d(TAG, "cache setup, check directory: " + cacheDir.absolutePath)
                cacheCounter = cacheCounter + 1
            } while (cacheDir.exists())
            cacheMap.put(envoyUrl, cacheDir.name)
            Log.d(TAG, "cache setup, create directory: " + cacheDir.absolutePath)
            cacheDir.mkdirs()

            if (envoyUrl.startsWith("v2ws://")) {
                Log.d(TAG, "found v2ray url: " + envoyUrl)
                handleV2rayWsSubmit(envoyUrl, captive_portal_url, hysteriaCert)
            } else if (envoyUrl.startsWith("v2srtp://")) {
                Log.d(TAG, "found v2ray url: " + envoyUrl)
                handleV2raySrtpSubmit(envoyUrl, captive_portal_url, hysteriaCert)
            } else if (envoyUrl.startsWith("v2wechat://")) {
                Log.d(TAG, "found v2ray url: " + envoyUrl)
                handleV2rayWechatSubmit(envoyUrl, captive_portal_url, hysteriaCert)
            } else if (envoyUrl.startsWith("hysteria://")) {
                Log.d(TAG, "found hysteria url: " + envoyUrl)
                handleHysteriaSubmit(envoyUrl, captive_portal_url, hysteriaCert
                )
            } else if (envoyUrl.startsWith("ss://")) {
                Log.d(TAG, "found ss url: " + envoyUrl)
                handleShadowsocksSubmit(envoyUrl, captive_portal_url, hysteriaCert)
            } else if (envoyUrl.startsWith("")) {
                Log.d(TAG, "found snowflake url: " + envoyUrl);
                handleSnowflakeSubmit(envoyUrl, captive_portal_url, hysteriaCert);
            } else if (envoyUrl.startsWith("http") || envoyUrl.startsWith("envoy")) {
                Log.d(TAG, "found http/envoy url: " + envoyUrl)
                handleHttpsSubmit(envoyUrl, captive_portal_url, hysteriaCert)
            } else {
                // prefix check should handle this but if not, batch count may not add up
                Log.w(TAG, "found unsupported url: " + envoyUrl)
            }
        }

        Log.d(TAG, "batch is finished")
        batchInProgress = false
    }

    private fun handleHttpsSubmit(
        url: String,
        captive_portal_url: String,
        hysteriaCert: String?
    ) {

        // nothing to parse at this time, leave url in string format

        httpsUrls.add(url)

        // add a slight delay to give the direct connection a chance
        Log.d(TAG, "submit url after a short delay for testing direct connection")
        ioScope.launch() {
            Log.d(TAG, "start https delay")
            delay(5000L) // wait 5 seconds
            Log.d(TAG, "end https delay")
            handleRequest(url, url, ENVOY_SERVICE_HTTPS, captive_portal_url, hysteriaCert)
        }
    }

    private fun handleShadowsocksSubmit(
        url: String,
        captive_portal_url: String,
        hysteriaCert: String?
    ) {

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
            handleRequest(
                url,
                LOCAL_URL_BASE + 1080,
                ENVOY_SERVICE_SS,
                captive_portal_url,
                hysteriaCert
            )
        }
    }

    private fun handleHysteriaSubmit(
        url: String,
        captive_portal_url: String,
        hysteriaCert: String?
    ) {
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
            val hysteriaPort =
                IEnvoyProxy.startHysteria(uri.host + ":" + uri.port, hystKey, hystCert)
            Log.d(TAG, "hysteria service started at " + LOCAL_URL_BASE + hysteriaPort)

            hysteriaUrls.add(LOCAL_URL_BASE + hysteriaPort)

            // method returns port immediately but service is not ready immediately
            Log.d(TAG, "submit url after a short delay for starting hysteria")
            ioScope.launch() {
                Log.d(TAG, "start hysteria delay")
                delay(10000L) // wait 10 seconds
                Log.d(TAG, "end hysteria delay")
                handleRequest(
                    url,
                    LOCAL_URL_BASE + hysteriaPort,
                    ENVOY_SERVICE_HYSTERIA,
                    captive_portal_url,
                    hysteriaCert
                )
            }
        }
    }

    private fun handleV2rayWsSubmit(
        url: String,
        captive_portal_url: String,
        hysteriaCert: String?
    ) {

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
                handleRequest(
                    url,
                    LOCAL_URL_BASE + v2wsPort,
                    ENVOY_SERVICE_V2WS,
                    captive_portal_url,
                    hysteriaCert
                )
            }
        }
    }

    private fun handleV2raySrtpSubmit(
        url: String,
        captive_portal_url: String,
        hysteriaCert: String?
    ) {

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
                handleRequest(
                    url,
                    LOCAL_URL_BASE + v2srtpPort,
                    ENVOY_SERVICE_V2SRTP,
                    captive_portal_url,
                    hysteriaCert
                )
            }
        }
    }

    private fun handleV2rayWechatSubmit(
        url: String,
        captive_portal_url: String,
        hysteriaCert: String?
    ) {

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
                handleRequest(
                    url,
                    LOCAL_URL_BASE + v2wechatPort,
                    ENVOY_SERVICE_V2WECHAT,
                    captive_portal_url,
                    hysteriaCert
                )
            }
        }
    }

    private fun handleSnowflakeSubmit(
        url: String,
        captive_portal_url: String,
        hysteriaCert: String?
    ) {

        val uri = URI(url)
        val ice = "stun:stun.l.google.com:19302,stun:stun.antisip.com:3478,stun:stun.bluesip.net:3478,stun:stun.dus.net:3478,stun:stun.epygi.com:3478,stun:stun.sonetel.com:3478,stun:stun.uls.co.za:3478,stun:stun.voipgate.com:3478,stun:stun.voys.nl:3478"
        val brokerUrl = ""
        val front = ""
        val ampCache = ""
        val rawQuery = uri.rawQuery
        val queries = rawQuery.split("&")
        for (i in 0 until queries.size) {
            val queryParts = queries[i].split("=")
            if (queryParts[0].equals("broker")) {
                brokerUrl = queryParts[1]
            } else if (queryParts[0].equals("ampCache")) {
                ampCache = queryParts[1]
            } else if (queryParts[0].equals("front")) {
                front = queryParts[1]
            }
        }
        // start v2ray wechat service
        if (blockedUrls.isNullOrEmpty()) {
            Log.e(TAG, "some arguments required for Snowflake service are missing")
        } else {
            // hardcoded values
            val logFile = ""
            val logToStateDir = false
            val keepLocalAddresses = true
            val unsafeLogging = false
            val maxPeers = 1
            // start
            val snowflakePort = IEnvoyProxy.startSnowflake(
                ice, brokerUrl, front, ampCache, logFile, logToStateDir, keepLocalAddresses,
                unsafeLogging, maxPeers)
            Log.d(TAG, "Snowflake service started at " + LOCAL_URL_BASE + snowflakePort)

            snowflakeUrls.add(LOCAL_URL_BASE + snowflakePort)

            // method returns port immediately but service is not ready immediately
            Log.d(TAG, "submit url after a short delay for starting Snowflake")
            ioScope.launch() {
                Log.d(TAG, "start snowflake delay")
                delay(1000L) // wait 1 second
                Log.d(TAG, "end snowflake delay")
                handleRequest(
                    url,
                    LOCAL_URL_BASE + snowflakePort,
                    ENVOY_SERVICE_V2WECHAT,
                    captive_portal_url,
                    hysteriaCert
                )
            }
        }
    }

    // test direct connection to avoid using proxy resources when not required
    private fun handleDirectRequest(
        directUrl: String,
        hysteriaCert: String?
    ) {

        Log.d(TAG, "create direct request to " + directUrl)

        val executor: Executor = Executors.newSingleThreadExecutor()
        val myBuilder = CronetEngine.Builder(applicationContext)
        val cronetEngine: CronetEngine = myBuilder
            .setUserAgent(DEFAULT_USER_AGENT).build()
        val requestBuilder = cronetEngine.newUrlRequestBuilder(
            directUrl,
            MyUrlRequestCallback(
                directUrl,
                directUrl,
                ENVOY_SERVICE_DIRECT,
                hysteriaCert
            ),
            executor
        )
        val request: UrlRequest = requestBuilder.build()
        request.start()
    }

    // TODO: do we just hard code captive portal url or add the default here?

    private fun handleRequest(
        originalUrl: String,
        envoyUrl: String,
        envoyService: String,
        captive_portal_url: String,
        hysteriaCert: String?,
        strategy: Int = 0
    ) {

        Log.d(TAG, "create request to " + captive_portal_url + " for url: " + envoyUrl)

        if (cacheMap.keys.contains(originalUrl)) {

            Log.d(TAG, "cache setup, found cache directory for " + originalUrl + " -> " + cacheMap.get(originalUrl))
            val cacheDir = File(applicationContext.cacheDir, cacheMap.get(originalUrl))

            try {
                val executor: Executor = Executors.newSingleThreadExecutor()
                val myBuilder = CronetEngine.Builder(applicationContext)
                val cronetEngine: CronetEngine = myBuilder
                    .enableBrotli(true)
                    .enableHttp2(true)
                    .enableQuic(true)
                    .setEnvoyUrl(envoyUrl)
                    .SetStrategy(strategy)
                    .setStoragePath(cacheDir.absolutePath)
                    .enableHttpCache(CronetEngine.Builder.HTTP_CACHE_DISK, 1 * 1024 * 1024) // 1 megabyte
                    .setUserAgent(DEFAULT_USER_AGENT)
                    .build()
                val requestBuilder = cronetEngine.newUrlRequestBuilder(
                    captive_portal_url,
                    MyUrlRequestCallback(
                        originalUrl,
                        envoyUrl,
                        envoyService,
                        hysteriaCert
                    ),
                    executor
                )
                val request: UrlRequest = requestBuilder.build()
                request.start()
                Log.d(TAG, "cache setup, cache cronet engine for url " + originalUrl)
                cronetMap.put(originalUrl, cronetEngine)
            } catch (ise: IllegalStateException) {
                Log.e(TAG, "cache setup, " + cacheDir.absolutePath + " could not be used")
            }
        } else {
            Log.e(TAG, "cache setup, could not find cache directory for " + originalUrl)
        }
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
                // TODO - how to stop shadowsocks service?
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
        } else if (snowflakeUrls.contains(envoyUrl)) {
            snowflakeUrls.remove(envoyUrl)
            if (snowflakeUrls.isEmpty()) {
                Log.d(TAG, "no snowflake urls remaining")
            } else {
                Log.d(TAG, "" + snowflakeUrls.size + " snowflake urls remaining, serice in use")
            }
        } else {
            Log.d(TAG, "url was not previously cached")
        }
    }

    fun getAdditionalUrls(urlInterval: Int, urlStart: Int, urlEnd: Int) {
        while (additionalUrlSources.isNotEmpty()) {

            Log.d(TAG, "get a list of additional urls")
            val url = URL(additionalUrlSources.removeAt(0))

            try {
                Log.d(TAG, "open connection: " + url)
                val connection = url.openConnection() as HttpURLConnection
                try {
                    Log.d(TAG, "set timeout")
                    connection.connectTimeout = 5000
                    Log.d(TAG, "connect")
                    connection.connect()
                } catch (e: SocketTimeoutException) {
                    Log.e(TAG, "socket timeout when connecting: " + e.localizedMessage)
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

                        Log.w(TAG, "received json: \n" + json)

                        val newUrls = Collections.synchronizedList(mutableListOf<String>())

                        val envoyObject = JSONObject(json)
                        val envoyUrlArray = envoyObject.getJSONArray("envoyUrls")

                        for (i in 0 until envoyUrlArray.length()) {
                            if (((urlInterval > 1) && ((i % urlInterval) > 0))
                                || ((urlStart >= 0) && (i >= urlStart))
                                || ((urlEnd >= 0) && (i <= urlEnd))
                            ) {
                                Log.d(TAG, "skip url at index " + i)
                                continue
                            }
                            if (submittedUrls.contains(envoyUrlArray.getString(i))) {
                                Log.d(TAG, "additional url " + envoyUrlArray.getString(i) + " has already been submitted")
                            } else if (additionalUrls.contains(envoyUrlArray.getString(i))) {
                                Log.d(TAG,"additional url " + envoyUrlArray.getString(i) + " was already found")
                            } else {
                                Log.d(TAG, "additional url " + envoyUrlArray.getString(i) + " has not been submitted yet")
                                newUrls.add(envoyUrlArray.getString(i))
                            }
                        }

                        broadcastUpdateSucceeded(url.toString(), newUrls)
                        additionalUrls.addAll(newUrls)
                        continue
                    } else {
                        Log.e(TAG, "response contained no json to parse")
                    }
                } catch (e: SocketTimeoutException) {
                    Log.e(TAG, "socket timeout when getting input: " + e.localizedMessage)
                } catch (e: FileNotFoundException) {
                    Log.e(TAG, "config file error: " + e.localizedMessage)
                } catch (e: Exception) {
                    Log.e(TAG, "unexpected error when reading file: " + e.localizedMessage)
                }
            } catch (e: Error) {
                Log.e(TAG, "connection error: " + e.localizedMessage)
            } catch (e: Exception) {
                Log.e(TAG, "unexpected error when opening connection: " + e.localizedMessage)
            }

            broadcastUpdateFailed(url.toString())
        }
    }

    fun submitAdditionalUrls(hysteriaCert: String?) {
        if (additionalUrls.isNullOrEmpty()) {
            // this check may be redundant
            Log.w(TAG, "no additional urls to submit")
            broadcastValidationFailure()
        } else {
            Log.d(TAG, "submit " + additionalUrls.size + " additional urls")

            val localIntent = Intent(ENVOY_BROADCAST_VALIDATION_CONTINUED)
            LocalBroadcastManager.getInstance(this@NetworkIntentService).sendBroadcast(localIntent)

            submitAdditional(this@NetworkIntentService, additionalUrls, hysteriaCert)
        }
    }

    companion object {
        private const val TAG = "NetworkIntentService"

        private const val TIME_LIMIT = 30000
        private const val ONE_HOUR_MS = 3600000
        private const val ONE_DAY_MS = 86400000
        private const val ONE_WEEK_MS = 604800000
        private const val TIME_SUFFIX = "_time"
        private const val COUNT_SUFFIX = "_count"

        val ioScope = CoroutineScope(Dispatchers.IO)
        var dnsttFlag = false

        /**
         * Starts this service to perform action Submit with the given parameters. If
         * the service is already performing a task this action will be queued.
         *
         * @see IntentService
         */
        @JvmStatic
        fun submit(
            context: Context,
            urls: List<String>,
            directUrls: List<String>?,
            hysteriaCert: String?,
            urlSources: List<String>?,
            urlInterval: Int,
            urlStart: Int,
            urlEnd: Int
        ) {
            Log.d(TAG, "jvm submit")
            processSubmit(context, urls, directUrls, hysteriaCert, urlSources, urlInterval, urlStart, urlEnd, true)
        }

        @JvmStatic
        fun submit(context: Context, urls: List<String>) {
            Log.d(TAG, "backwards compatible submit")
            processSubmit(context, urls, null, null, null, 1, -1, -1, true)
        }

        // no jvm annotation, not for external use
        fun submitAdditional(context: Context, urls: List<String>, hysteriaCert: String?) {
            Log.d(TAG, "dnstt submit")
            processSubmit(context, urls, null, hysteriaCert, null, 1, -1, -1, false)
        }

        // no jvm annotation, not for external use
        fun processSubmit(
            context: Context,
            urls: List<String>,
            directUrls: List<String>?,
            hysteriaCert: String?,
            urlSources: List<String>?,
            urlInterval: Int,
            urlStart: Int,
            urlEnd: Int,
            firstAttempt: Boolean
        ) {
            Log.d(TAG, "process submit")
            val intent = Intent(context, NetworkIntentService::class.java).apply {
                action = ACTION_SUBMIT
                putStringArrayListExtra(EXTRA_PARAM_SUBMIT, ArrayList<String>(urls))
                if (!directUrls.isNullOrEmpty()) {
                    putStringArrayListExtra(
                        EXTRA_PARAM_DIRECT,
                        directUrls as java.util.ArrayList<String>?
                    )
                }
                if (!hysteriaCert.isNullOrEmpty()) {
                    putExtra(EXTRA_PARAM_CERT, hysteriaCert)
                }
                if (!urlSources.isNullOrEmpty()) {
                    putStringArrayListExtra(
                        EXTRA_PARAM_SOURCES,
                        urlSources as java.util.ArrayList<String>?
                    )
                }
                if (urlInterval > 1) {
                    putExtra(EXTRA_PARAM_INTERVAL, urlInterval)
                }
                if (urlStart >= 0) {
                    putExtra(EXTRA_PARAM_START, urlStart)
                }
                if (urlEnd >= 0) {
                    putExtra(EXTRA_PARAM_END, urlEnd)
                }
                putExtra(EXTRA_PARAM_FIRST, firstAttempt)
            }
            context.startService(intent)
        }
    }

    inner class MyUrlRequestCallback(private val originalUrl: String,
                                     private val envoyUrl: String,
                                     private val envoyService: String,
                                     private val hysteriaCert: String?) : UrlRequest.Callback() {

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
            // update batch
            Log.d(TAG, "batch cleanup, remove valid url: " + originalUrl)
            if (envoyService.equals(ENVOY_SERVICE_DIRECT)) {
                Log.d(TAG, "direct url " + originalUrl + " was valid, but do not update lists")
            } else {
                this@NetworkIntentService.currentBatchChecked.add(originalUrl)
                this@NetworkIntentService.currentServiceChecked.add(envoyService)
                this@NetworkIntentService.currentBatch.remove(originalUrl)
            }

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
                    editor.putInt(originalUrl + COUNT_SUFFIX, 0)
                    editor.apply()

                    val localIntent = Intent(ENVOY_BROADCAST_VALIDATION_SUCCEEDED).apply {
                        // puts the validation status into the intent
                        putExtra(ENVOY_DATA_URL_SUCCEEDED, envoyUrl)
                        putExtra(ENVOY_DATA_SERVICE_SUCCEEDED, envoyService)
                        putExtra(ENVOY_DATA_VALIDATION_MS, calculateValidationTime())
                    }
                    LocalBroadcastManager.getInstance(this@NetworkIntentService).sendBroadcast(localIntent)

                    // check whether batch is complete
                    if (this@NetworkIntentService.currentBatch.size > 0) {
                        Log.d(TAG, "" + this@NetworkIntentService.currentBatch.size + " urls remaining in current batch")
                    } else  {
                        Log.d(TAG, "current batch is empty, but a valid url was found: " + this@NetworkIntentService.validUrls.get(0))

                        broadcastBatchStatus(ENVOY_BROADCAST_BATCH_SUCCEEDED)
                    }
                } else {
                    // logs captive portal url used to validate envoy url
                    Log.e(TAG, "onSucceeded method called for " + info.url + " (" + envoyUrl + ") / " + envoyService + " -> got " + info.httpStatusCode + " response code so tested url is invalid")
                    handleInvalidUrl()
                }
            } else {
                Log.w(TAG, "onSucceeded method called but UrlResponseInfo was null")
            }

            cacheCleanup()
        }

        override fun onFailed(
            request: UrlRequest?,
            info: UrlResponseInfo?,
            error: CronetException?
        ) {
            // update batch
            Log.d(TAG, "batch cleanup, remove invalid url: " + originalUrl)
            if (envoyService.equals(ENVOY_SERVICE_DIRECT)) {
                Log.d(TAG, "direct url " + originalUrl + " was invalid, but do not update lists")
            } else {
                this@NetworkIntentService.currentBatchChecked.add(originalUrl)
                this@NetworkIntentService.currentServiceChecked.add(envoyService)
                this@NetworkIntentService.currentBatch.remove(originalUrl)
            }

            // logs captive portal url used to validate envoy url
            Log.e(TAG, "onFailed method called for invalid url " + info?.url + " (" + envoyUrl + ") / " + envoyService + " -> " + error?.message)
            handleInvalidUrl()

            cacheCleanup()
        }

        private fun cacheCleanup() {

            val engine = cronetMap.get(originalUrl)
            if (engine == null) {
                Log.w(TAG, "cache cleanup, could not find cached cronet engine for url " + originalUrl)
            } else {
                Log.d(TAG, "cache cleanup, found cached cronet engine for url " + originalUrl)
                engine.shutdown()
                Log.d(TAG, "cache cleanup, shut down cached cronet engine for url " + originalUrl)
            }

            val cacheName = cacheMap.get(originalUrl)
            if (cacheName == null) {
                Log.w(TAG, "cache cleanup, could not find cached directory for url " + originalUrl)
                return
            } else {
                Log.d(TAG, "cache cleanup, found cached directory " + cacheName + " for url " + originalUrl)
            }

            // clean up http cache dir
            val cacheDir = File(applicationContext.cacheDir, cacheName)

            // clean up old cached data
            cacheDir.let {
                if (it.exists()) {
                    val files = it.listFiles()
                    if (files != null) {
                        for (file in files) {
                            if (file.deleteRecursively()) {
                                Log.d(TAG, "cache cleanup, delete " + file.absolutePath)
                            } else {
                                Log.e(TAG, "cache cleanup, failed to delete " + file.absolutePath)
                            }
                        }
                    }
                }
            }
            Log.d(TAG, "cache cleanup, delete " + cacheDir.absolutePath)
            cacheDir.deleteRecursively()
        }

        fun handleInvalidUrl() {
            handleCleanup(envoyUrl)

            // store failed urls so they are not attempted again
            val currentTime = System.currentTimeMillis()
            val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this@NetworkIntentService)
            val failureCount = sharedPreferences.getInt(originalUrl + COUNT_SUFFIX, 0)
            val editor: SharedPreferences.Editor = sharedPreferences.edit()
            editor.putLong(originalUrl + TIME_SUFFIX, currentTime)
            editor.putInt(originalUrl + COUNT_SUFFIX, failureCount + 1)
            editor.apply()

            // broadcast intent with invalid urls so application can handle errors
            this@NetworkIntentService.invalidUrls.add(envoyUrl)
            val localIntent = Intent(ENVOY_BROADCAST_VALIDATION_FAILED).apply {
                // puts the validation status into the intent
                putExtra(ENVOY_DATA_URL_FAILED, envoyUrl)
                putExtra(ENVOY_DATA_SERVICE_FAILED, envoyService)
            }
            LocalBroadcastManager.getInstance(this@NetworkIntentService).sendBroadcast(localIntent)

            if (this@NetworkIntentService.currentBatch.size > 0) {
                // check whether current batch of urls have failed
                Log.d(TAG, "" + this@NetworkIntentService.currentBatch.size + " urls remaining in current batch")
            } else if (this@NetworkIntentService.validUrls.size > 0) {
                // a valid url was found, do not continue
                Log.d(TAG, "current batch is empty, but a valid url was previously found")

                broadcastBatchStatus(ENVOY_BROADCAST_BATCH_SUCCEEDED)
            } else if ((this@NetworkIntentService.shuffledUrls.size + this@NetworkIntentService.shuffledHttps.size) > 0) {
                // check whether all submitted urls have failed
                Log.d(TAG, "current batch is empty, " + (this@NetworkIntentService.shuffledUrls.size + this@NetworkIntentService.shuffledHttps.size) + " submitted urls remaining")

                broadcastBatchStatus(ENVOY_BROADCAST_BATCH_FAILED)

                if (checkValidationTime()) {
                    // time remaining, continue
                    handleBatch(hysteriaCert)
                } else {
                    // time expired, do not continue
                    Log.w(TAG, "time expired, cannot continue with next batch")
                    broadcastValidationFailure()
                    return
                }
            } else {

                broadcastBatchStatus(ENVOY_BROADCAST_BATCH_FAILED)

                if (this@NetworkIntentService.additionalUrls.isNullOrEmpty()) {
                    // all available urls have failed, broadcast validation time
                    Log.w(TAG, "all available urls have failed, cannot continue")
                    broadcastValidationFailure()
                    return
                } else if (checkValidationTime()) {
                    // all urls in original submission have failed, submit additional urls for validation
                    Log.w(TAG, "all urls submitted have failed, validate additional urls")
                    submitAdditionalUrls(hysteriaCert)
                } else {
                    // time expired, do not continue
                    Log.w(TAG, "time expired, cannot continue with additional urls")
                    broadcastValidationFailure()
                    return
                }
            }
        }
    }
}
