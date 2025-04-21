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

            val timeElapsed = loopTimer.stop()

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

            // report test results, keep track of things, etc
            // calls the user provided callback
            // it's important this is called after settings.connected()
            // so the selected service isn't stopped :)
            reporter.testComplete(test, res, false)

            if (res) {
                util.stopTestPassed(test)
                state.connectIfNeeded(test)
            } else {
                // report test failure. failed tests will not be retried until time passes
                util.stopTestFailed(test)
            }
        }

        // TODO: this is an opportunity to fetch more URLs to try
        Log.d(WTAG, "testUrl " + id + " is out of URLs")
        reportEndState()
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
            // Pick a working DoH server
            state.dns.init()
            // if one was picked, pass it over to the Go code to use
            // state.dns.chosenServer?.let {
            //     val server = it
            //     state.iep?.let {
            //         it.setDOHServer(server)
            //     }
            // }
            if (state.dns.chosenServer != null && state.iep != null) {
                IEnvoyProxy.setDOHServer(state.dns.chosenServer)
            }

            Log.d(TAG, "startEnvoy3: ${Thread.currentThread().name}")

            Log.d(TAG, "startEnvoy3: ${Thread.currentThread().name}")

            // initialize the go code
            // should we use a subdir? This is (mostly?) used for
            // the PT state directory in Lyrebird
            state.emissary.init(context.cacheDir.path)

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
        val timeElapsed = System.currentTimeMillis() - startTime.get()
        if (timeElapsed > TIME_LIMIT) {
            Log.d(TAG, "time expired, end test")
            return true
        } else {
            Log.d(TAG, "time remaining, continue test")
            return false
        }
    }

    private fun reportEndState() {
        if (testComplete.compareAndSet(false, true)) {
            Log.d(TAG, "need to report status")
        } else {
            Log.d(TAG, "status already reported")
            return
        }
        val timeElapsed = System.currentTimeMillis() - startTime.get()
        if (EnvoyNetworking.envoyConnected) {
            // url found
            Log.d(TAG, "RESULT: PASSED - " + timeElapsed / 1000)
            callback.reportTestStatus(EnvoyTestStatus.PASSED, timeElapsed)
        } else if (testCount.get() < 1) {
            // nothing to test
            Log.d(TAG, "RESULT: EMPTY - " + timeElapsed / 1000)
            callback.reportTestStatus(EnvoyTestStatus.EMPTY, timeElapsed)
        } else if (testCount.get() == blockedCount.get()) {
            // all tests blocked
            Log.d(TAG, "RESULT: BLOCKED - " + timeElapsed / 1000)
            callback.reportTestStatus(EnvoyTestStatus.BLOCKED, timeElapsed)
        } else if (testCount.get() == (blockedCount.get() + failedCount.get())) {
            // all tests blocked or failed
            Log.d(TAG, "RESULT: FAILED - " + timeElapsed / 1000)
            callback.reportTestStatus(EnvoyTestStatus.FAILED, timeElapsed)
        } else if (testCount.get() > (blockedCount.get() + failedCount.get())) {
            // testing incomplete, timeout?
            Log.d(TAG, "RESULT: TIMEOUT - " + timeElapsed / 1000)
            callback.reportTestStatus(EnvoyTestStatus.TIMEOUT, timeElapsed)
        } else {
            // unknown?
            Log.d(TAG, "RESULT: UNKNOWN - " + timeElapsed / 1000)
            callback.reportTestStatus(EnvoyTestStatus.UNKNOWN, timeElapsed)
        }
    }
}