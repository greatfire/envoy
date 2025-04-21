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

    private var testComplete = AtomicBoolean()


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
                break
            } else if (isUrlBlocked(test.url)) {
                val blocked = blockedCount.incrementAndGet()
                Log.d(WTAG, "URL BLOCKED, SKIP - " + test.url + " / " + blocked)
                continue
            }

            val loopTimer = Timer()

            Log.d(WTAG, "CONTINUE TESTING URL " + test.url)

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
                // Test was successful

                // direct connection, enable this even if something else
                // tested as working first
                if (test.testType == EnvoyServiceType.DIRECT) {
                    EnvoyNetworking.connected(test)
                }

                // do we already have a working connection?
                // if so, no need to do anything
                if (!EnvoyNetworking.envoyConnected) {
                    EnvoyNetworking.connected(test)
                }

                // report status
                callback.reportUrlSuccess(proxyUri, test.testType, timeElapsed)

                // we're done
                // XXX it's technically possible for a proxy to "win" this
                // race while a direct connection works

                // we have a working connection, stop wasting resources ;-)
                stopWorkers()
                break;

            } else {
                // report failure
                callback.reportUrlFailure(proxyUri, test.testType, timeElapsed)

                // failed?
                if (test.testType == EnvoyServiceType.DIRECT) {
                    Log.d(WTAG, "DIRECT FAILED - " + test.url)
                } else {
                    val failed = failedCount.incrementAndGet()
                    Log.d(WTAG, "URL FAILED - " + test.url + " / " + failed)

                    // store failed urls so they are not attempted again
                    // XXX Ever? When? Why? What are the rules here?
                    val currentTime = System.currentTimeMillis()
                    val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
                    val failureCount = sharedPreferences.getInt(test.url + COUNT_SUFFIX, 0)
                    val editor: SharedPreferences.Editor = sharedPreferences.edit()
                    editor.putLong(test.url + TIME_SUFFIX, currentTime)
                    editor.putInt(test.url + COUNT_SUFFIX, failureCount + 1)
                    editor.apply()
                }
            }
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
    private suspend fun startWorkers() = coroutineScope {
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

    private suspend fun startEnvoy() = coroutineScope {
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
            val test = EnvoyTest(
                EnvoyServiceType.DIRECT, EnvoyConnectionTests.directUrl)
            envoyTests.add(test)
        }
        // shuffle the rest of the URLs
        envoyTests.addAll(EnvoyConnectionTests.envoyTests.shuffled())

        Log.i(TAG, "EnvoyConnectWorker starting with "
                + envoyTests.size
                + " URLs to test")

        startEnvoy()
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

        val runCount = blockedCount.get() + failedCount.get()
        val allBlocked = (testCount.get() < 1)
        val allFailed = (testCount.get() == runCount)
        val timeout = (testCount.get() > runCount)

        val result = when {
            EnvoyNetworking.envoyConnected -> EnvoyTestStatus.PASSED
            (testCount.get() < 1) -> EnvoyTestStatus.EMPTY
            allBlocked -> EnvoyTestStatus.BLOCKED
            allFailed -> EnvoyTestStatus.FAILED
            timeout -> EnvoyTestStatus.TIMEOUT
            else -> EnvoyTestStatus.UNKNOWN
        }

        Log.d(TAG, "Result: $result time: " + timeElapsed / 1000)
        callback.reportTestStatus(result, timeElapsed)
    }
}