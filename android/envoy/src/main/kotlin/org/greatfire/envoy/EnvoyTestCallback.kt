package org.greatfire.envoy

import android.content.Context
import java.net.URI

interface EnvoyTestCallback {

    fun reportTestSuccess(testedUrl: String, testedService: EnvoyServiceType, timeElapsed: Long)

    fun reportTestFailure(testedUrl: String, testedService: EnvoyServiceType, timeElapsed: Long)

    fun reportTestBlocked(testedUrl: String, testedService: EnvoyServiceType)

    fun reportOverallStatus(testStatus: EnvoyTestStatus, timeElapsed: Long)

    //is this viable?
    // fun getContext(): Context
}