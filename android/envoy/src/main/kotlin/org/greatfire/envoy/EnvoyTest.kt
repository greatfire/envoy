package org.greatfire.envoy

import android.util.Log

// This is a simple data class for tracking Envoy tests
//
// The url should be the one provided by the caller
// testType lets us know how to treat that URL
// some transports have an additional URL, e.g. a SOCKS5 URL
// for a PT, that gets stored in proxyUrl

data class EnvoyTest(
    var testType: EnvoyServiceType,
    var url: String,
) {
    companion object {
        private const val TAG = "EnvoyTest"
    }

    var proxyUrl: String? = null
    private var timer: Timer? = null

    private fun getTimer(): Timer {
        if (timer == null) {
            timer = Timer()
        }
        return timer!!
    }

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

    fun startTest() {
        getTimer() // this starts the timer as a side effect
    }

    fun stopTimer(): Long {
        return getTimer().stop()
    }

    fun timeSpent(): Long {
        return getTimer().timeSpent()
    }
}