package org.greatfire.envoy

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.preference.PreferenceManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.*
import org.greatfire.envoy.NetworkIntentService.Companion
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

import IEnvoyProxy.IEnvoyProxy

/*
    Establish a connection to a supported proxy

    This copies the EnvoyTest objects out of EnvoyConectionTests, shuffles
    them, and starts testing several in parallel.

    The actul test methods also live in EnvoyConectionTests

*/

class EnvoyConnectWorker(
    val context: Context,
    val params: WorkerParameters
) : CoroutineWorker(context, params) {

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

    // collection of all tests that need to be run
    val tests = EnvoyConnectionTests()

    // this ArrayDeque is our working copy of the tests
    private val envoyTests = ArrayDeque<EnvoyTest>()
    private val jobs = mutableListOf<Job>()

    private val state = EnvoyState.getInstance()
    private val util = EnvoyTestUtil.getInstance()

    // "Worker"
    // This is run in EnvoyNetworking.concurrency number of coroutines
    // It effectively limits the number of servers we test at a time
    suspend fun testUrls(id: Int) {
        // add worker ID to the TAG
        val WTAG = TAG + "-" + id

        while (true) {
            val test = envoyTests.removeFirstOrNull()
            if (test == null) {
                // Log.d(WTAG, "NO TESTS LEFT, BREAK")
                // XXX ask for more URLs?
                break
            }  else if (state.connected.get()) {
                // Log.d(WTAG, "ALREADY CONNECTED, BREAK")
                break
            } else if (util.isTimeExpired()) {
                // Log.d(WTAG, "TIME EXPIRED, BREAK")
                break
            } else if (util.isUrlBlocked(test)) {
                // starts the timer and updates the tally
                util.startTest(test)
                // Log.d(WTAG, "URL BLOCKED, SKIP - " + test)
                util.stopTestBlocked(test)
                continue
            } else {
                // starts the timer and updates the tally
                util.startTest(test)
            }

            // MNB: temp, replace test.testType string with enum?
            var serviceType = EnvoyServiceType.UNKNOWN

            // start the timer
            test.startTest()

            // is there some better way to structure this? It's going to
            // get ungainly
            val proxyUri = Uri.parse(test.url)
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
                EnvoyServiceType.OKHTTP_MASQUE -> {
                    tests.testMasqueOkHttp(test)
                }
                EnvoyServiceType.CRONET_MASQUE -> {
                    tests.testMasqueCronet(test, context)
                }
                EnvoyServiceType.OKHTTP_PROXY -> {
                    // url is a proxy URL, so set it as the proxyUrl as well
                    // all the other services set something in proxyUrl
                    // so this avoids a special case in the interceptor
                    test.proxyUrl = test.url
                    tests.testStandardProxy(proxyUri)
                }
                EnvoyServiceType.CRONET_PROXY -> {
                    tests.testCronetProxy(test, context)
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
                ENVOY_PROXY_HTTP_ECH -> {
                    serviceType = EnvoyServiceType.HTTP_ECH
                    tests.testECHProxy(test)
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

            // Report success if the test was successful
            if (res) {
                settings.connected(test)
                // stopWorkers()
                // break;
            }

            // report test results, keep track of things, etc
            // calls the user provided callback
            // it's important this is called after settings.connected()
            // so the selected service isn't stopped :)
            reporter.testComplete(test, res, false)

            if (res) {
                // We found a working connection!

                // Report the success
                util.stopTestPassed(test)
                // Use this connection if we haven't found a working on already
                state.connectIfNeeded(test)
            } else {
                // report test failure. failed tests will not be retried until time passes
                util.stopTestFailed(test)
            }
        }

        // TODO: this is an opportunity to fetch more URLs to try
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
            "Launching ${state.concurrency} coroutines for ${envoyTests.size} tests")

        // start timer
        util.startAllTests()

        for (i in 1..state.concurrency) {
            // Log.d(TAG, "Launching worker: " + i)
            var job = launch {
                testUrls(i)
            }
            jobs.add(job)
        }

        // wait for jobs to complete
        jobs.joinAll()

        // MNB jobs have all completed, report overall status
        util.testsComplete()

        // Log.d(TAG, "EnvoyConnectWorker workers are done?")
    }

    private suspend fun startEnvoy() = coroutineScope {
        // Create IEP controller
        // we need to do this now so we can call setDOHServer below
        state.InitIEnvoyProxy()

        launch {
            Log.d(TAG, "startEnvoy2: ${Thread.currentThread().name}")
            // Pick a working DoH server
            state.dns.init()
            // if one was picked, pass it over to the Go code to use
            if (state.dns.chosenServer != null) {
                IEnvoyProxy.setDOHServer(state.dns.chosenServer)
            }

            Log.d(TAG, "startEnvoy4: ${Thread.currentThread().name}")
            // start test workers
            // this depends (in some cases) on dns.init() completing
            startWorkers()

            Log.d(TAG, "startEnvoy5: ${Thread.currentThread().name}")
        }
    }

    //
    // Main entry point
    override suspend fun doWork(): Result {
        // test direct connection first
        if (EnvoyConnectionTests.directUrl != "") {
            val test = EnvoyTest(EnvoyServiceType.DIRECT, EnvoyConnectionTests.directUrl)
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

        try {
            startEnvoy()
        } catch (e: Exception) {
            Log.e(TAG, "Starting Envoy failed: $e")
        }
        // if we return failure, the job is re-run, I think?
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