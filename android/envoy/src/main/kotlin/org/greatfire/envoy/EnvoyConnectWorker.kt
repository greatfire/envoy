package org.greatfire.envoy

import org.greatfire.envoy.transport.*

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.*

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

    companion object {
        private const val TAG = "EnvoyConnectWorker"
    }

    // collection of all tests that need to be run
    val tests = EnvoyConnectionTests()

    // this ArrayDeque is our working copy of the tests
    private val transports = ArrayDeque<Transport>()
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
            val test = transports.removeFirstOrNull()
            if (test == null) {
                // No tests left
                // XXX ask for more URLs?
                break
            }  else if (state.connected.get()) {
                // we're already connected
                break
            } else if (util.isTimeExpired()) {
                // Time Expired
                break
            } else if (util.isUrlBlocked(test)) {
                // URL is blocked

                // starts the timer and updates the tally
                util.start(test)
                // Log.d(WTAG, "URL BLOCKED, SKIP - " + test)
                util.stopTestBlocked(test)
                continue
            } else {
                // starts the timer and updates the tally
                util.start(test)
            }

            // each test type has a corresponding implementation of startTest
            Log.d(TAG, "Starting test ${test.testType}")
            val res = test.startTest(context)

            if (res) {
                // We found a working connection!

                // Report the success
                util.stopTestPassed(test)
                // Use this connection if we haven't found a working on already
                state.connectIfNeeded(test)
            } else {
                // stop the service
                Log.d(TAG, "STOP ${test.testType}")
                // please stop moving this call out of the worker, I want this
                // to be explictly done in the worker, not in a side effect
                test.stopService()

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
            "Launching ${state.concurrency} coroutines for ${transports.size} tests")

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

        // jobs have all completed, report overall status
        util.testsComplete()
    }

    private suspend fun startEnvoy() = coroutineScope {
        // Create IEP controller
        // we need to do this now so we can call setDOHServer below
        state.InitIEnvoyProxy()

        launch {
            // Pick a working DoH server
            state.dns.init()
            // if one was picked, pass it over to the Go code to use
            if (state.dns.chosenServer != null) {
                IEnvoyProxy.setDOHServer(state.dns.chosenServer)
            }

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
        EnvoyConnectionTests.directTest?.let {
            transports.add(it)
        }

        // We preserve the original list of tests in EnvoyNetworking
        // for use if we need to reconnect. This is just our working
        // copy

        // shuffle the rest of the URLs
        transports.addAll(EnvoyConnectionTests.transports.shuffled())

        Log.i(TAG, "EnvoyConnectWorker starting with "
                + transports.size
                + " URLs to test")

        try {
            startEnvoy()
        } catch (e: Exception) {
            Log.e(TAG, "Starting Envoy failed: $e")
            Log.e(TAG, Log.getStackTraceString(e))
        }
        // if we return failure, the job is re-run, I think?
        return Result.success()
    }
}