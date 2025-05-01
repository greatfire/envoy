package org.greatfire.envoy

import android.content.Context
import android.util.Log
import androidx.work.*

/*
    This object provides an external interface for setting up network connections with Envoy.
    It can also be accessed from the EnvoyConnectWorker and the EnvoyInterceptor.
*/

class EnvoyNetworking {

    companion object {
        private const val TAG = "EnvoyNetworking"

        // Should the Interceptor enable a direct connection if the app
        // requests appear to be working (i.e. returning 200 codes)
        var passivelyTestDirect = true
        var initialized = false
    }

    private val state = EnvoyState.getInstance()

    // Public functions, this is the primary public interface for Envoy
    fun addEnvoyUrl(url: String): EnvoyNetworking {
        EnvoyConnectionTests.addEnvoyUrl(url)

        // let Java callers chain
        return this
    }

    // use a custom test URL and response code
    // default is ("https://www.google.com/generate_204", 204)
    fun setTestUrl(url: String, responseCode: Int): EnvoyNetworking {
        EnvoyConnectionTests.testUrl = url
        EnvoyConnectionTests.testResponseCode = responseCode

        return this
    }

    // Set the direct URL to the site, if this one works, Envoy is bypassed
    //
    // The caller should either pass a URL here or set passivelyTestDirect
    // direct to true. It's not a problem to do both, but probably not
    // necessary. Doing neither will disable direct connections
    fun setDirectUrl(newVal: String): EnvoyNetworking {
        EnvoyConnectionTests.directUrl = newVal

        return this
    }

    // Set the callback for reporting status to the main application
    fun setCallback(callback: EnvoyTestCallback): EnvoyNetworking {
        state.callback = callback

        return this
    }

    // Provide a context reference from the main application
    fun setContext(context: Context): EnvoyNetworking {
        state.ctx = context

        return this
    }

    fun connect(): EnvoyNetworking {
        initialized = true
        Log.d(TAG, "🏄‍♂️🏄‍♂️🏄‍♂️ Starting Envoy connect...")

        val workRequest = OneTimeWorkRequestBuilder<EnvoyConnectWorker>()
            // connecting to the proxy is a high priority task
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()

        WorkManager
            .getInstance(state.ctx!!)
            .enqueue(workRequest)

        return this
    }
}