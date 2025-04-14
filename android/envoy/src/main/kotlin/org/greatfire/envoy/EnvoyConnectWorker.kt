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

    // MNB read about workers

    companion object {
        private const val TAG = "EnvoyConnectWorker"
    }

    private val envoyTests = ArrayDeque<EnvoyTest>()
    private val jobs = mutableListOf<Job>()

    // This is run in EnvoyNetworking.concurrency number of coroutines
    // It effectively limits the number of servers we test at a time
    suspend fun testUrls(id: Int) {
        var test = envoyTests.removeFirstOrNull() // MNB: thread safe?

        val WTAG = TAG + "-" + id

        // Log.d(WTAG, "worker: " + id)
        // Log.d(WTAG, "Thread: " + Thread.currentThread().name)

        while (test != null) {
            val proxyUri = URI(test.url)
            Log.d(WTAG, "Test: " + test)

            val res = when(test.testType) {
                ENVOY_PROXY_DIRECT -> {
                    Log.d(WTAG, "Testing Direct Connection")
                    EnvoyNetworking.testDirectConnection()
                }
                ENVOY_PROXY_OKHTTP_ENVOY -> {
                    Log.d(WTAG, "Testing Envoy URL: " + test.url)
                    EnvoyNetworking.testEnvoyOkHttp(proxyUri)
                }
                ENVOY_PROXY_OKHTTP_PROXY -> {
                    Log.d(WTAG, "Testing Proxyed: " + test.url)
                    EnvoyNetworking.testStandardProxy(proxyUri)
                }
                else -> {
                    Log.e(WTAG, "Unsupported test type: " + test.testType)
                    false
                }
            }

            if (res) {
                Log.d(WTAG, "SUCCESSFUL URL: " + test)
                // direct connection, enable this even if something else
                // tested as working first
                if (test.testType == ENVOY_PROXY_DIRECT) {
                    EnvoyNetworking.connected(ENVOY_PROXY_DIRECT, test.url)
                }

                // did someone else win?
                if (!EnvoyNetworking.envoyConnected) {
                    EnvoyNetworking.connected(test.testType, test.url)
                }

                // we're done
                // XXX it's technically possible for a proxy to "win" this
                // race while a direct connection works
                stopWorkers()
            } // MNB: no stop on failure?


            Log.d(WTAG, "FAILED URL: " + test)
            test = envoyTests.removeFirstOrNull()
        }

        Log.d(WTAG, "testUrl " + id + " is out of URLs")
    }

    // the worker ends up stopping itself this way, that seems bad?
    private suspend fun stopWorkers() {
        for (j in jobs) {
            j.cancel()
        }
        for (j in jobs) {
            j.join()
        }
        // MNB: uh...?  sop on first success, else don't stop
    }

    private fun startWorkers() = runBlocking {
        for (i in 1..EnvoyNetworking.concurrency) {
            Log.d(TAG, "Launching worker: " + i)
            var job = launch(Dispatchers.IO) {
                testUrls(i)
            }
            jobs.add(job)
        }
        // MNB: do urls > concurrency ever get tested <- concurrency is thread count not tests run
        // MNB: launch all then join all?

        Log.d(TAG, "Go coroutines...")

        for (j in jobs) {
            j.join() // MNB: i think this blocks on one then blocks on the next, etc.
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
            val test = EnvoyTest(ENVOY_PROXY_DIRECT, EnvoyNetworking.directUrl)
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