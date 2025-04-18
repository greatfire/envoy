package org.greatfire.envoy

import java.net.URI

interface EnvoyTestCallback {

    fun reportUrlSuccess(testedUri: URI, testedService: EnvoyServiceType, timeElapsed: Long)

    fun reportUrlFailure(testedUri: URI, testedService: EnvoyServiceType, timeElapsed: Long)

    fun reportTestStatus(testStatus: EnvoyTestStatus, timeElapsed: Long)

}