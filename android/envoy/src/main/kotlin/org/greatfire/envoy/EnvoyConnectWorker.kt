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

    // simple counters to infer status
    private var testCount = AtomicInteger()
    private var blockedCount = AtomicInteger()
    private var failedCount = AtomicInteger()

    private var startTime = AtomicLong()

    private var foundUrl = AtomicBoolean()
    private var testComplete = AtomicBoolean()



    // This is run in EnvoyNetworking.concurrency number of coroutines
    // It effectively limits the number of servers we test at a time
    suspend fun testUrls(id: Int) {
        // var test = envoyTests.removeFirstOrNull() // MNB: thread safe?

        val WTAG = TAG + "-" + id

        // Log.d(WTAG, "worker: " + id)
        // Log.d(WTAG, "Thread: " + Thread.currentThread().name)

        while (true) {
            val test = envoyTests.removeFirstOrNull()
            if (test == null) {
                Log.d(WTAG, "TESTS COMPLETE, BREAK")
                break
            }  else if (isTimeExpired()) {
                Log.d(WTAG, "TIME EXPIRED, BREAK")
                break
            } else if (foundUrl.get()) {
                Log.d(WTAG, "ALREADY FOUND A URL, BREAK")
                break
            } else if (isUrlBlocked(test.url)) {
                val blocked = blockedCount.incrementAndGet()
                Log.d(WTAG, "URL BLOCKED, SKIP - " + test.url + " / " + blocked)
                continue
            }

            val loopStart = System.currentTimeMillis()

            Log.d(WTAG, "CONTINUE TESTING URL " + test.url)

            val proxyUri = URI(test.url)
            Log.d(WTAG, "Test job: " + test)

            // MNB: temp, replace test.testType string with enum?
            var serviceType = EnvoyServiceType.UNKNOWN

            // is there some better way to structure this? It's going to
            // get ungainly
            val res = when(test.testType) {
                ENVOY_PROXY_DIRECT -> {
                    serviceType = EnvoyServiceType.DIRECT
                    tests.testDirectConnection()
                }
                ENVOY_PROXY_OKHTTP_ENVOY -> {
                    serviceType = EnvoyServiceType.OKHTTP_ENVOY
                    tests.testEnvoyOkHttp(proxyUri)
                }
                ENVOY_PROXY_CRONET_ENVOY -> {
                    serviceType = EnvoyServiceType.CRONET_ENVOY
                    tests.testCronetEnvoy(test, context)
                }
                ENVOY_PROXY_OKHTTP_PROXY -> {
                    serviceType = EnvoyServiceType.OKHTTP_PROXY
                    tests.testStandardProxy(proxyUri)
                }
                ENVOY_PROXY_HTTP_ECH -> {
                    serviceType = EnvoyServiceType.HTTP_ECH
                    tests.testECHProxy(test)
                }
                ENVOY_PROXY_HYSTERIA2 -> {
                    serviceType = EnvoyServiceType.HYSTERIA2
                    Log.d(WTAG, "Testing Hysteria")
                    tests.testHysteria2(proxyUri)
                }
                else -> {
                    Log.e(WTAG, "Unsupported test type: " + test.testType)
                    false
                }
            }

            val timeElapsed = System.currentTimeMillis() - loopStart

            if (res) {
                Log.d(WTAG, "SUCCESSFUL URL: " + test)
                foundUrl.set(true)
                callback.reportUrlSuccess(proxyUri, serviceType, timeElapsed)
                // direct connection, enable this even if something else
                // tested as working first
                if (test.testType == ENVOY_PROXY_DIRECT) {
                    Log.d(WTAG, "DIRECT WORKS, USE THAT: " + test.url)
                    EnvoyNetworking.connected(ENVOY_PROXY_DIRECT, test.url)
                }

                // did someone else win?
                if (!EnvoyNetworking.envoyConnected) {
                    Log.d(WTAG, "NOT CONNECTED YET, USE URL: " + test.url)
                    if (test.testType == ENVOY_PROXY_HTTP_ECH) {
                        // proxying ECH connections through the Go code
                        // is a little weird for now
                        EnvoyNetworking.connected(
                            ENVOY_PROXY_OKHTTP_ENVOY, test.extra!!)
                    } else {
                        EnvoyNetworking.connected(test.testType, test.url)
                    }
                } else {
                    if (test.testType == ENVOY_PROXY_DIRECT) {
                        Log.d(WTAG, "USING DIRECT URL")
                    } else {
                        Log.d(WTAG, "CONNECTED ALREADY, SKIP URL: " + test.url)
                    }
                }

                // we're done
                // XXX it's technically possible for a proxy to "win" this
                // race while a direct connection works
                // stopWorkers()
                // MNB: with flags and checks to break loop, do we need to force a stop?
                //   only concern i can think of would be if a single test kept running too long
                break;

            } else {

                callback.reportUrlFailure(proxyUri, serviceType, timeElapsed)

                // failed?
                if (test.testType == ENVOY_PROXY_DIRECT) {
                    Log.d(WTAG, "DIRECT FAILED - " + test.url)
                } else {
                    val failed = failedCount.incrementAndGet()
                    Log.d(WTAG, "URL FAILED - " + test.url + " / " + failed)

                    // store failed urls so they are not attempted again
                    val currentTime = System.currentTimeMillis()
                    val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
                    val failureCount = sharedPreferences.getInt(test.url + COUNT_SUFFIX, 0)
                    val editor: SharedPreferences.Editor = sharedPreferences.edit()
                    editor.putLong(test.url + TIME_SUFFIX, currentTime)
                    editor.putInt(test.url + COUNT_SUFFIX, failureCount + 1)
                    editor.apply()
                }

            }

            //Log.d(WTAG, "FAILED URL: " + test)
        }

        // TODO: this is an opportunity to fetch more URLs
        // to try
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
    private fun startWorkers() {
        Log.i(TAG,
            "Launching ${EnvoyNetworking.concurrency} coroutines for ${envoyTests.size} tests")

        for (i in 1..EnvoyNetworking.concurrency) {
            Log.d(TAG, "Launching worker: " + i)
            var job = launch(Dispatchers.IO) {
                testUrls(i)
            }
            jobs.add(job)
        }

        jobs.joinAll()

        Log.d(TAG, "EnvoyConnectWorker is done")

        // MNB: ...or do we report end state here?
    }

    private fun startWork() = coroutineScope {
        launch {
            // Pick a working DoH server
            EnvoyNetworking.dns.init()

            // initialize the go code

            // should we use a subdir? This is (mostly?) used for
            // the PT state directory in Lyrebird
            EnvoyNetworking.emissary.init(context.filesDir.path)

            // start test workers
            startWorkers()
        }
    }

    override suspend fun doWork(): Result {

        // reset things
        startTime.set(System.currentTimeMillis())
        testCount.set(EnvoyConnectionTests.envoyTests.size) // don't count direct test
        blockedCount.set(0)
        failedCount.set(0)
        foundUrl.set(false)
        testComplete.set(false)

        envoyTests.clear()
        jobs.clear()

        // sanity check
        if (testCount.get() < 1) {
            Log.d(TAG, "NOTHING TO TEST")
            reportEndState()
            return Result.success() // success?
        }

        // test direct connection first
        if (EnvoyConnectionTests.directUrl != "") {
            // testUrls.add(EnvoyNetworking.directUrl)
            val test = EnvoyTest(ENVOY_PROXY_DIRECT, EnvoyConnectionTests.directUrl)
            envoyTests.add(test)
        }
        // shuffle the rest of the URLs
        envoyTests.addAll(EnvoyConnectionTests.envoyTests.shuffled())

        Log.i(TAG, "EnvoyConnectWorker starting with "
                + envoyTests.size
                + " URLs to test")

        startWork()
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
        if (foundUrl.get()) {
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