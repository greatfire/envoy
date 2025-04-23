package org.greatfire.envoy

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.PreferenceManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.net.URI
import kotlinx.coroutines.*
import org.greatfire.envoy.NetworkIntentService.Companion
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/*
    Establish a connection to an Envoy Proxy
*/
class EnvoyConnectWorker(
    val context: Context, val params: WorkerParameters, val callback: EnvoyTestCallback
) : CoroutineWorker(context, params) {

    // MNB read about workers

    val tests = EnvoyConnectionTests()

    companion object {
        private const val TAG = "EnvoyConnectWorker"
        private val settings = EnvoyNetworkingSettings.getInstance()

        // these seem to be related to the "blocked" logic
        private const val TIME_LIMIT = 60000 // make configurable?
        private const val ONE_HOUR_MS = 3600000
        private const val ONE_DAY_MS = 86400000
        private const val ONE_WEEK_MS = 604800000
        private const val TIME_SUFFIX = "_time"
        private const val COUNT_SUFFIX = "_count"
    }

    // this ArrayDeque is our working copy of the tests
    private val envoyTests = ArrayDeque<EnvoyTest>()
    private val jobs = mutableListOf<Job>()

    private val reporter = EnvoyTestReporter()

    // This is run in EnvoyNetworking.concurrency number of coroutines
    // It effectively limits the number of servers we test at a time
    suspend fun testUrls(id: Int) {
        // XXX split this up in to like 3 functions

        val WTAG = TAG + "-" + id

        while (true) {
            val test = envoyTests.removeFirstOrNull()
            if (test == null) {
                break
            }  else if (isTimeExpired()) {
                Log.d(WTAG, "TIME EXPIRED, BREAK")
                // XXX shouldn't we just use a coroutine timeout?
                stopWorkers()
                break
            } else if (isUrlBlocked(test.url)) {
                Log.d(WTAG, "URL BLOCKED, SKIP - " + test)
                reporter.testComplete(test, false, true)
                continue
            }

            val proxyUri = URI(test.url)
            Log.d(WTAG, "Test job: " + test)

            // is there some better way to structure this? It's going to
            // get ungainly
            val res = when(test.testType) {
                EnvoyServiceType.DIRECT -> {
                    tests.testDirectConnection()
                }
                EnvoyServiceType.OKHTTP_ENVOY -> {
                    tests.testEnvoyOkHttp(proxyUri)
                }
                EnvoyServiceType.CRONET_ENVOY -> {
                    tests.testCronetEnvoy(test, context)
                }
                EnvoyServiceType.OKHTTP_PROXY -> {
                    tests.testStandardProxy(proxyUri)
                }
                EnvoyServiceType.HTTP_ECH -> {
                    tests.testECHProxy(test)
                }
                EnvoyServiceType.SHADOWSOCKS -> {
                    tests.testShadowsocks(test)
                }
                EnvoyServiceType.HYSTERIA2 -> {
                    Log.d(WTAG, "Testing Hysteria")
                    tests.testHysteria2(test)
                }
                EnvoyServiceType.V2SRTP -> {
                    tests.testV2RaySrtp(test)
                }
                EnvoyServiceType.V2WECHAT -> {
                    tests.testV2RayWechat(test)
                }
                else -> {
                    Log.e(WTAG, "Unsupported test type: " + test.testType)
                    false
                }
            }

            // report test results, keep track of things, etc
            // calls the user provided callback
            reporter.testComplete(test, res, false)

            if (res) {
                // Test was successful

                // direct connection, enable this even if something else
                // tested as working first
                if (test.testType == EnvoyServiceType.DIRECT) {
                    settings.connected(test)
                }

                // do we already have a working connection?
                // if so, no need to do anything (but report)
                if (!settings.envoyConnected) {
                    settings.connected(test)
                }

                // we're done
                // XXX it's technically possible for a proxy to "win" this
                // race while a direct connection works

                // we have a working connection, stop wasting resources ;-)
                stopWorkers()
                break;
            }
        }

        // TODO: this is an opportunity to fetch more URLs
        // to try
        Log.d(WTAG, "testUrl " + id + " is out of URLs")
        reporter.reportEndState()
    }

    // the worker ends up stopping itself this way, that seems bad?
    private suspend fun stopWorkers() {
        jobs.forEach { it.cancel() }
        jobs.joinAll()
    }

    // Launch EnvoyNetworking.concurrency number of coroutines
    // to test connection methods
    private suspend fun startWorkers() = coroutineScope {
        Log.i(TAG,
            "Launching ${settings.concurrency} coroutines for ${envoyTests.size} tests")

        for (i in 1..settings.concurrency) {
            Log.d(TAG, "Launching worker: " + i)
            var job = launch {
                testUrls(i)
            }
            jobs.add(job)
        }

        jobs.joinAll()

        Log.d(TAG, "EnvoyConnectWorker is done")

        // MNB: ...or do we report end state here?
    }

    private suspend fun startEnvoy() = coroutineScope {

        launch {
            // Pick a working DoH server
            settings.dns.init()

            // initialize the go code

            // should we use a subdir? This is (mostly?) used for
            // the PT state directory in Lyrebird
            settings.emissary.init(context.filesDir.path)

            // start test workers
            startWorkers()
        }
    }

    //
    // Main entry point
    override suspend fun doWork(): Result {

        envoyTests.clear()
        jobs.clear()

        // sanity check
        if (EnvoyConnectionTests.envoyTests.size < 1) {
            Log.d(TAG, "NOTHING TO TEST")
            reporter.reportEndState()
            return Result.success() // success?
        }

        // test direct connection first
        if (EnvoyConnectionTests.directUrl != "") {
            // testUrls.add(EnvoyNetworking.directUrl)
            val test = EnvoyTest(
                EnvoyServiceType.DIRECT, EnvoyConnectionTests.directUrl)
            envoyTests.add(test)
        }
        // We preserve the original list of tests in EnvoyNetworking
        // for use if we need to reconnect. This is just our working
        // copy

        // shuffle the rest of the URLs
        envoyTests.addAll(EnvoyConnectionTests.envoyTests.shuffled())

        Log.i(TAG, "EnvoyConnectWorker starting with "
                + envoyTests.size
                + " URLs to test")

        startEnvoy()
        return Result.success()
    }

    // this doesn't belong inside the connect worker? maybe in the reporter?
    private fun isUrlBlocked(url: String): Boolean {

        // disable this feature for debugging
        if (BuildConfig.BUILD_TYPE == "debug") {
            Log.d(TAG, "debug build, ignore time limit and submit")
            return false
        } else {
            Log.d(TAG, "release build, check time limit before submitting")
        }

        val currentTime = System.currentTimeMillis()
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val failureTime = preferences.getLong(url + TIME_SUFFIX, 0)
        val failureCount = preferences.getInt(url + COUNT_SUFFIX, 0)

        val sanitizedUrl = UrlUtil.sanitizeUrl(url)

        if ((failureCount in 1..3 && currentTime - failureTime < ONE_HOUR_MS * failureCount)
            || (failureCount == 4 && currentTime - failureTime < ONE_DAY_MS)
            || (failureCount >= 5 && currentTime - failureTime < ONE_WEEK_MS)) {
            Log.d(TAG, "time limit has not expired for url(" + failureTime + "), do not submit: " + sanitizedUrl)
            return true
        } else {
            Log.d(TAG, "time limit expired for url(" + failureTime + "), submit again: " + sanitizedUrl)
            return false
        }
    }

    private fun isTimeExpired(): Boolean {
        val timeElapsed = reporter.timeElapsed()
        if (timeElapsed > TIME_LIMIT) {
            Log.d(TAG, "time expired, end test")
            return true
        } else {
            Log.d(TAG, "time remaining, continue test")
            return false
        }
    }
}