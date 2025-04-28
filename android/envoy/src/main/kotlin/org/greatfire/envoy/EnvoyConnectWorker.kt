package org.greatfire.envoy

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.net.URI
import kotlinx.coroutines.*

/*
    Establish a connection to an Envoy Proxy
*/

class EnvoyConnectWorker(
    val context: Context,
    val params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "EnvoyConnectWorker"
        // MNB retry/timeout logic moved to util class
    }

    // collection of all tests that need to be run
    val tests = EnvoyConnectionTests()

    // this ArrayDeque is our working copy of the tests
    private val envoyTests = ArrayDeque<EnvoyTest>()
    private val jobs = mutableListOf<Job>()

    // MNB these are singletons now so no need to store them in the companion
    private val state = EnvoyState.getInstance()
    private val util = EnvoyTestUtil.getInstance()

    // This is run in EnvoyNetworking.concurrency number of coroutines
    // It effectively limits the number of servers we test at a time
    suspend fun testUrls(id: Int) {
        // XXX split this up in to like 3 functions

        val WTAG = TAG + "-" + id

        while (true) {
            val test = envoyTests.removeFirstOrNull()
            if (test == null) {
                Log.d(WTAG, "NO TESTS LEFT, BREAK")
                break
            }  else if (util.isTimeExpired()) {
                Log.d(WTAG, "TIME EXPIRED, BREAK")
                // XXX shouldn't we just use a coroutine timeout?
                // MNB this creates a fuzzier time limit that allows tests in progress to complete
                break
            } else if (util.isUrlBlocked(test)) {
                // starts the timer and updates the tally
                util.startTest(test)
                Log.d(WTAG, "URL BLOCKED, SKIP - " + test.url)
                util.stopTestBlocked(test)
                continue
            } else {
                // starts the timer and updates the tally
                util.startTest(test)
                Log.d(WTAG, "EXECUTE TEST FOR - " + test.url)
            }

            // is there some better way to structure this? It's going to
            // get ungainly
            val proxyUri = URI(test.url)
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

            if (res) {
                // first success sets connected flag, other successes stop services,
                // report success in either case. if test returned with updated flag,
                // create a cronet engine (depending on selected service)
                state.connectIfNeeded(util.stopTestPassed(test))
            } else {
                // report test failure. failed tests will not be retried until time passes
                util.stopTestFailed(test)
            }
        }

        // TODO: this is an opportunity to fetch more URLs to try
        Log.d(WTAG, "testUrl " + id + " is out of URLs")
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

        // clear state variables, start timer
        util.reset()

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

        Log.d(TAG, "EnvoyConnectWorker workers are done?")
    }

    private suspend fun startEnvoy() = coroutineScope {
        Log.d(TAG, "startEnvoy1: ${Thread.currentThread().name}")
        // launch (newSingleThreadContext("EnvoyConnectThread")) {
        launch {
            Log.d(TAG, "startEnvoy2: ${Thread.currentThread().name}")
            // Pick a working DoH server
            state.dns.init()

            Log.d(TAG, "startEnvoy3: ${Thread.currentThread().name}")
            // initialize the go code

            // should we use a subdir? This is (mostly?) used for
            // the PT state directory in Lyrebird
            state.emissary.init(context.filesDir.path)

            Log.d(TAG, "startEnvoy4: ${Thread.currentThread().name}")
            // start test workers
            startWorkers()

            Log.d(TAG, "startEnvoy5: ${Thread.currentThread().name}")
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
            util.testsComplete()
            return Result.success()
        }

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

        startEnvoy()
        return Result.success()
    }
}