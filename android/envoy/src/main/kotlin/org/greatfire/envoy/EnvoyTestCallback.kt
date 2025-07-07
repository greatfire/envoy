package org.greatfire.envoy

import android.content.Context
import java.net.URI

interface EnvoyTestCallback {

    fun reportTestSuccess(testedUrl: String, testedService: String, timeElapsed: Long)

    fun reportTestFailure(testedUrl: String, testedService: String, timeElapsed: Long)

    fun reportTestBlocked(testedUrl: String, testedService: String)

    fun reportOverallStatus(testStatus: String, timeElapsed: Long)

    //is this viable?
    // fun getContext(): Context
}
