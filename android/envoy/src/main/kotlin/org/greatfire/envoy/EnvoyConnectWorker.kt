package org.greatfire.envoy

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.net.URI
import kotlinx.coroutines.*

/*
    Establish a connection to an Envoy Proxy
*/
class EnvoyConnectWorker(
    context: Context, params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "EnvoyConnectWorker"
    }

    private val envoyTests = ArrayDeque<EnvoyTest>()
    private val jobs = mutableListOf<Job>()

    // This is run in EnvoyNetworking.concurrency number of coroutines
    // It effectively limits the number of servers we test at a time
    suspend fun testUrls(id: Int) {
        var test = envoyTests.removeFirstOrNull()

        val WTAG = TAG + "-" + id

        // Log.d(WTAG, "worker: " + id)
        // Log.d(WTAG, "Thread: " + Thread.currentThread().name)

        while (test != null) {
            val proxyUri = URI(test.url)
            Log.d(WTAG, "Testing type: " + test.testType + " URL: " + test.url)

            val res = when(test.testType) {
                ENVOY_TEST_DIRECT -> {
                    Log.d(WTAG, "Testing Direct Connection")
                    EnvoyNetworking.testDirectConnection()
                }
                ENVOY_TEST_OKHTTP_ENVOY -> {
                    Log.d(WTAG, "Testing Envoy URL")
                    EnvoyNetworking.testEnvoyOkHttp(proxyUri)
                }
                ENVOY_TEST_OKHTTP_PROXY -> {
                    Log.d(WTAG, "Testing Proxyed")
                    EnvoyNetworking.testStandardProxy(proxyUri)
                }
                else -> {
                    Log.e(WTAG, "Unsupported test type: " + test.testType)
                    false
                }
            }

            Log.d(WTAG, "URL: " + test.url + " worked?: " + res)
            test = envoyTests.removeFirstOrNull()
        }

        Log.d(WTAG, "testUrl " + id + " is out of URLs")
    }

    private fun startWorkers() = runBlocking {
        for (i in 1..EnvoyNetworking.concurrency) {
            Log.d(TAG, "Launching worker: " + i)
            var job = launch(Dispatchers.IO) {
                testUrls(i)
            }
            jobs.add(job)
        }

        Log.d(TAG, "Go coroutines...")

        for (j in jobs) {
            j.join()
        }

        Log.d(TAG, "coroutines done?")
    }

    override suspend fun doWork(): Result {
        // reset things
        envoyTests.clear()
        jobs.clear()

        // test direct connection first
        if (EnvoyNetworking.directUrl != "") {
            // testUrls.add(EnvoyNetworking.directUrl)
            val test = EnvoyTest(ENVOY_TEST_DIRECT, EnvoyNetworking.directUrl)
            envoyTests.add(test)
        }
        // shuffle the rest of the URLs
        envoyTests.addAll(EnvoyNetworking.envoyTests.shuffled())

        Log.i(TAG, "EnvoyConnectWorker starting with "
                + envoyTests.size
                + " URLs to test")

        startWorkers()
        return Result.success()
    }
}