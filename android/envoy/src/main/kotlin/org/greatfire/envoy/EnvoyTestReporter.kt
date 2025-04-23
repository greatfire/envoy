package org.greatfire.envoy

import android.content.SharedPreferences
import android.util.Log
import androidx.preference.PreferenceManager

// EnvoyTest instances are added to this Reporter as they complete.
// The reporter keeps track of counts and things to report on success
// or failure

class EnvoyTestReporter() {
    companion object {
        private const val TAG = "EnvoyTestReporter"

        private const val TIME_SUFFIX = "_time"
        private const val COUNT_SUFFIX = "_count"
    }

    // simple counters to infer status
    private var testCount = 0
    private var blockedCount = 0
    private var failedCount = 0
    // I think we can just set this on instantiation?
    private var startTime = System.currentTimeMillis()


    // This is a stand-in for the callback, hook it up later
    // the callback should probably be passed in the constructor?
    inner class TempCallback() {
        fun reportUrlFailure(url: String, type: EnvoyServiceType, timeElapsed: Long) { return }
        fun reportUrlSuccess(url: String, type: EnvoyServiceType, timeElapsed: Long) { return }
        fun reportTestStatus(result: EnvoyTestStatus, timeElapsed: Long) { return }
    }
    private var callback = TempCallback()

    private fun reportSuccess(test: EnvoyTest) {
        callback.reportUrlSuccess(test.url, test.testType, test.timeSpent())
    }

    private fun reportFailure(test: EnvoyTest) {

        val settings = EnvoyNetworkingSettings.getInstance()

        if (test.testType != EnvoyServiceType.DIRECT) {
            // store failed urls so they are not attempted again
            // XXX Ever? When? Why? What are the rules here?
            val currentTime = System.currentTimeMillis()
            // XXX this needs a context, but probably needs to move anyway
            // so grab one for now
            val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(settings.ctx!!)
            val failureCount = sharedPreferences.getInt(test.url + COUNT_SUFFIX, 0)
            val editor: SharedPreferences.Editor = sharedPreferences.edit()
            editor.putLong(test.url + TIME_SUFFIX, currentTime)
            editor.putInt(test.url + COUNT_SUFFIX, failureCount + 1)
            editor.apply()
        }

        callback.reportUrlFailure(test.url, test.testType, test.timeSpent())
    }

    fun testComplete(test: EnvoyTest, result: Boolean, blocked: Boolean) {
        val timeElapsed = test.stopTimer()

        testCount++
        when {
            blocked -> blockedCount++
            result == false -> failedCount++
            result == true -> reportSuccess(test)
        }
    }

    fun timeElapsed(): Long {
        return System.currentTimeMillis() - startTime
    }

    fun reportEndState() {

        val settings = EnvoyNetworkingSettings.getInstance()

        val timeElapsed = System.currentTimeMillis() - startTime

        val runCount = blockedCount + failedCount
        val allBlocked = (testCount < 1)
        val allFailed = (testCount == runCount)
        val timeout = (testCount > runCount)

        val result = when {
            settings.envoyConnected -> EnvoyTestStatus.PASSED
            (testCount < 1) -> EnvoyTestStatus.EMPTY
            allBlocked -> EnvoyTestStatus.BLOCKED
            allFailed -> EnvoyTestStatus.FAILED
            else -> {
                Log.e(TAG, "Internal error, EnvoyTestStatus is unknown?")
                EnvoyTestStatus.UNKNOWN
            }
        }

        callback.reportTestStatus(result, timeElapsed)
    }
}