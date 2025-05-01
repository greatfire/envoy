package org.greatfire.envoy

import android.content.SharedPreferences
import android.util.Log
import androidx.preference.PreferenceManager
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

// EnvoyTest instances are added to this Reporter as they complete.
// The reporter keeps track of counts and things to report on success
// or failure

class EnvoyTestUtil() {

    companion object {
        private const val TAG = "EnvoyTestUtil"

        private const val TIME_LIMIT = 60000 // make configurable?
        private const val ONE_HOUR_MS = 3600000
        private const val ONE_DAY_MS = 86400000
        private const val ONE_WEEK_MS = 604800000
        private const val TIME_SUFFIX = "_time"
        private const val COUNT_SUFFIX = "_count"

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

    // moving this here from state so it can be set as test results are handled
    // do we need to save selected test info here too?
    var connected = AtomicBoolean(false)
    var service = AtomicInteger(EnvoyServiceType.UNKNOWN.ordinal)
    var activeConnection: EnvoyTest? = null
    val additionalWorkingConnections = mutableListOf<EnvoyTest>()

    fun reset() {
        Log.d(TAG, "RESET")

        testCount.set(0)
        blockedCount.set(0)
        failedCount.set(0)
        startTime.set(System.currentTimeMillis())
        connected.set(false)
        service.set(EnvoyServiceType.UNKNOWN.ordinal)
        activeConnection = null
        additionalWorkingConnections.clear()
    }

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
        if (BuildConfig.BUILD_TYPE == "debug") {
            Log.d(TAG, "debug build, ignore time limit and submit")
            return false
        } else {
            Log.d(TAG, "release build, check time limit before submitting")
        }

        val currentTime = System.currentTimeMillis()
        val preferences = PreferenceManager.getDefaultSharedPreferences(state.ctx!!)
        val failureTime = preferences.getLong(test.url + TIME_SUFFIX, 0)
        val failureCount = preferences.getInt(test.url + COUNT_SUFFIX, 0)

        val sanitizedUrl = UrlUtil.sanitizeUrl(test.url)

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

    fun startTest(test: EnvoyTest) {
        test.startTimer()
        val count = testCount.incrementAndGet()
        Log.d(TAG, "TEST COUNT UPDATED: " + count)
    }

    fun stopTestPassed(test: EnvoyTest): EnvoyTest {
        test.stopTimer()

        // do we need to start envoy?
        if (connected.compareAndSet(false, true)) {
            // if this is the first test that passed, set flag and save type (and...?)
            // too many flags?
            service.set(test.testType.ordinal)
            activeConnection = test
            test.selectedService = true
            Log.d(TAG, "TEST PASSED, SET TYPE TO " + test.testType)
        } else if(test.testType == EnvoyServiceType.DIRECT) {
            // if direct connection worked, override type from previous success
            // stop service?  stop cronet engine?
            service.set(EnvoyServiceType.DIRECT.ordinal)
            val currentActiveConnection = activeConnection
            currentActiveConnection?.let {
                Log.d(TAG, "USE DIRECT, SET ASIDE PREVIOUS CONNECTION: " + currentActiveConnection.testType)
                currentActiveConnection.selectedService = false
                currentActiveConnection.stopService()
                additionalWorkingConnections.add(currentActiveConnection)
                activeConnection = null
            }
            test.selectedService = true
            Log.d(TAG, "TEST PASSED, OVERRIDE TYPE TO " + test.testType)
        } else {
            // if this one wasn't selected, and the service is
            // still running, stop it now
            test.stopService()
            // if we're storing additional active connections do we still stop the service?
            additionalWorkingConnections.add(test)
            Log.d(TAG, "TEST PASSED, BUT ALREADY SET TYPE, STOPPED SERVICE")
        }

        if (test.testType != EnvoyServiceType.DIRECT) {
            // passed, remove retry interval
            // this may not be thread safe, but it shouldn't be called concurrently for the same url
            val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(state.ctx!!)
            val editor: SharedPreferences.Editor = sharedPreferences.edit()
            editor.remove(test.url + TIME_SUFFIX)
            editor.remove(test.url + COUNT_SUFFIX)
            editor.apply()
            Log.d(TAG, "REMOVED PREFS: " + test.url + TIME_SUFFIX + " / " + test.url + COUNT_SUFFIX)
        }

        state.callback!!.reportTestSuccess(test.url, test.testType, test.timeSpent())
        // return test with updated state (including selected flag)
        return test
    }

    fun stopTestFailed(test: EnvoyTest) {
        test.stopTimer()
        test.stopService()

        val count = failedCount.incrementAndGet()
        Log.d(TAG, "FAILED COUNT UPDATED: " + count)

        if (test.testType != EnvoyServiceType.DIRECT) {
            // failed, update retry interval
            // this may not be thread safe, but it shouldn't be called concurrently for the same url
            val currentTime = System.currentTimeMillis()
            val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(state.ctx!!)
            val failureCount = sharedPreferences.getInt(test.url + COUNT_SUFFIX, 0)
            val editor: SharedPreferences.Editor = sharedPreferences.edit()
            editor.putLong(test.url + TIME_SUFFIX, currentTime)
            editor.putInt(test.url + COUNT_SUFFIX, failureCount + 1)
            editor.apply()
            Log.d(
                TAG,
                "SAVED PREFS: " + test.url + TIME_SUFFIX + " - " + currentTime
                        + " / " + test.url + COUNT_SUFFIX + " - " + (failureCount + 1)
            )
        }

        state.callback!!.reportTestFailure(test.url, test.testType, test.timeSpent())
    }

    fun stopTestBlocked(test: EnvoyTest) {
        test.stopTimer()
        val count = blockedCount.incrementAndGet()
        Log.d(TAG, "BLOCKED COUNT UPDATED: " + count)

        state.callback!!.reportTestBlocked(test.url, test.testType)
    }

    fun testsComplete() {

        val time = System.currentTimeMillis() - startTime.get()

        val passed = connected.get()
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