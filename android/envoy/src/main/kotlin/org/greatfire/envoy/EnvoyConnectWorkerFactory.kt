package org.greatfire.envoy

import android.content.Context
import android.util.Log
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters

class EnvoyConnectWorkerFactory(private val callback: EnvoyTestCallback) : WorkerFactory() {
    override fun createWorker(appContext: Context,
                              workerClassName: String,
                              workerParameters: WorkerParameters
    ): EnvoyConnectWorker {
        Log.d("FOO", "CUSTOM FACTORY, CREATE WORKER")
        return EnvoyConnectWorker(appContext, workerParameters, callback)
    }
}