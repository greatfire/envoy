package org.greatfire.envoy

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong


// MNB: Can you document this? What is it's job?

/*
This seems to be:
* keeping track of statistics
* reporting back to the caller
* holding some test related logic
*/

class EnvoyTestUtil() {

    companion object {
        private const val TAG = "EnvoyTestUtil"

        // MNB: can you document how these constants are used?
        // What are the rules for the "blocked" functionality?
        private const val TIME_LIMIT = 60000 // make configurable?
        private const val FIVE_MINUTES_MS = 300000
        private const val ONE_HOUR_MS = 3600000
        private const val ONE_DAY_MS = 86400000
        private const val ONE_WEEK_MS = 604800000

        // Singleton
        @Volatile
        private var instance: EnvoyTestUtil? = null

        fun getInstance() = instance ?: synchronized(this) {
            instance ?: EnvoyTestUtil().also { instance = it }
        }
    }

    private val state = EnvoyState.getInstance()

    // simple counters to infer status
    var testCount = AtomicInteger(0)
    var blockedCount = AtomicInteger(0)
    var failedCount = AtomicInteger(0)

    var startTime = AtomicLong(System.currentTimeMillis())

    val preferences = EnvoyPrefs()

    fun isTimeExpired(): Boolean {
        val time = System.currentTimeMillis() - startTime.get()
        if (time > TIME_LIMIT) {
            Log.d(TAG, "time expired, stop testing")
            return true
        } else {
            Log.d(TAG, "time remaining, continue testing")
            return false
        }
    }

    fun isUrlBlocked(test: EnvoyTest): Boolean {

        // disable this feature for debugging
        if (!state.backoffEnabled) {
            Log.d(TAG, "backoff flag not set, ignore time limit and submit")
            return false
        } else {
            Log.d(TAG, "backoff flag set, check time limit before submitting")
        }

        val currentTime = System.currentTimeMillis()
        val failureTime = preferences.getFailureTimeForUrl(test.envoyUrl)
        val failureCount = preferences.getFailureCountForUrl(test.envoyUrl)

        val sanitizedUrl = UrlUtil.sanitizeUrl(test.envoyUrl)

        // backoff retries to avoid repeatedly hitting potentially blocked endpoints
        // first wait 5/10/15 minutes, then an hour, then a day
        // temporary blocks never seem to be unblocked within seconds
        if ((failureCount in 1..3 && currentTime - failureTime < FIVE_MINUTES_MS * failureCount)
            || (failureCount == 4 && currentTime - failureTime < ONE_HOUR_MS)
            || (failureCount > 5 && currentTime - failureTime < ONE_DAY_MS)) {
            Log.d(TAG, "time limit has not expired for url(" + failureTime + "), do not submit: " + sanitizedUrl)
            return true
        } else {
            Log.d(TAG, "time limit expired for url(" + failureTime + "), submit again: " + sanitizedUrl)
            return false
        }
    }

    fun startAllTests() {
        startTime.set(System.currentTimeMillis())
    }

    fun startTest(test: EnvoyTest) {
        test.startTimer()
        val count = testCount.incrementAndGet()
        // Log.d(TAG, "TEST COUNT UPDATED: " + count)
    }

    // Stop the test, it passed
    fun stopTestPassed(test: EnvoyTest): EnvoyTest {
        test.stopTimer()

        if (test.testType != EnvoyServiceType.DIRECT) {
            // passed, remove retry interval
            // this may not be thread safe, but it shouldn't be called concurrently for the same url
            preferences.clearUrlFailure(test.envoyUrl)
        }

        state.callback!!.reportTestSuccess(test.envoyUrl, test.testType, test.timeSpent())
        // return test with updated state (including selected flag)
        return test
    }

    // stop the test, it failed
    fun stopTestFailed(test: EnvoyTest) {
        test.stopTimer()
        test.stopService()

        val count = failedCount.incrementAndGet()
        Log.d(TAG, "FAILED COUNT UPDATED: " + count)

        if (test.testType != EnvoyServiceType.DIRECT) {
            // failed, update retry interval
            // this may not be thread safe, but it shouldn't be called concurrently for the same url
            val currentTime = System.currentTimeMillis()
            preferences.incrementUrlFailure(test.envoyUrl, currentTime)
        }

        state.callback!!.reportTestFailure(test.envoyUrl, test.testType, test.timeSpent())
    }

    fun stopTestBlocked(test: EnvoyTest) {
        test.stopTimer()
        val count = blockedCount.incrementAndGet()
        Log.d(TAG, "BLOCKED COUNT UPDATED: " + count)

        state.callback!!.reportTestBlocked(test.envoyUrl, test.testType)
    }

    fun testsComplete() {

        val time = System.currentTimeMillis() - startTime.get()

        val passed = state.connected.get()
        val count = testCount.get()
        val runCount = blockedCount.get() + failedCount.get()
        val allBlocked = (count < 1)
        val allFailed = (count == runCount)
        val timeout = (count > runCount)

        val result = when {
            passed -> EnvoyTestStatus.PASSED
            (count < 1) -> EnvoyTestStatus.EMPTY
            allBlocked -> EnvoyTestStatus.BLOCKED
            allFailed -> EnvoyTestStatus.FAILED
            timeout -> EnvoyTestStatus.TIMEOUT
            else -> {
                Log.e(TAG, "Internal error, EnvoyTestStatus is unknown?")
                EnvoyTestStatus.UNKNOWN
            }
        }

        state.callback!!.reportOverallStatus(result, time)
    }
}
