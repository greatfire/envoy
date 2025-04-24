package org.greatfire.envoy

import android.content.Context
import java.net.URI

interface EnvoyTestCallback {

    fun reportTestSuccess(testedUri: URI, testedService: EnvoyServiceType, timeElapsed: Long)

    fun reportTestFailure(testedUri: URI, testedService: EnvoyServiceType, timeElapsed: Long)

    fun reportOverallStatus(testStatus: EnvoyTestStatus, timeElapsed: Long)

    //is this viable?
    // fun getContext(): Context
}