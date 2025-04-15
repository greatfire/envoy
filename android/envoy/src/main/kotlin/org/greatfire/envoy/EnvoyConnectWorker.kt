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
    val context: Context, val params: WorkerParameters
) : CoroutineWorker(context, params) {

    val tests = EnvoyConnectionTests()

    companion object {
        private const val TAG = "EnvoyConnectWorker"
    }

    // this ArrayDeque is our working copy of the tests
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
            Log.d(WTAG, "Test job: " + test)

            // is there some better way to structure this? It's going to
            // get ungainly
            val res = when(test.testType) {
                ENVOY_PROXY_DIRECT -> {
                    tests.testDirectConnection()
                }
                ENVOY_PROXY_OKHTTP_ENVOY -> {
                    tests.testEnvoyOkHttp(proxyUri)
                }
                ENVOY_PROXY_CRONET_ENVOY -> {
                    tests.testCronetEnvoy(test, context)
                }
                ENVOY_PROXY_OKHTTP_PROXY -> {
                    tests.testStandardProxy(proxyUri)
                }
                ENVOY_PROXY_HTTP_ECH -> {
                    tests.testECHProxy(test)
                }
                ENVOY_PROXY_HYSTERIA2 -> {
                    Log.d(WTAG, "Testing Hysteria")
                    tests.testHysteria2(proxyUri)
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
                    if (test.testType == ENVOY_PROXY_HTTP_ECH) {
                        // proxying ECH connections through the Go code
                        // is a little weird for now
                        EnvoyNetworking.connected(
                            ENVOY_PROXY_OKHTTP_ENVOY, test.extra!!)
                    } else {
                        EnvoyNetworking.connected(test.testType, test.url)
                    }
                }

                // we're done
                // XXX it's technically possible for a proxy to "win" this
                // race while a direct connection works
                stopWorkers()
            }


            Log.d(WTAG, "FAILED URL: " + test)
            test = envoyTests.removeFirstOrNull()
        }

        // TODO: this is an opportunity to fetch more URLs
        // to try
        Log.d(WTAG, "testUrl " + id + " is out of URLs")
    }

    // the worker ends up stopping itself this way, that seems bad?
    private suspend fun stopWorkers() {
        jobs.forEach { it.cancel() }
        jobs.joinAll()
    }

    // Launch EnvoyNetworking.concurrency number of coroutines
    // to test connection methods
    private fun startWorkers() = runBlocking {
        Log.i(TAG,
            "Launching ${EnvoyNetworking.concurrency} coroutines for ${envoyTests.size} tests")

        for (i in 1..EnvoyNetworking.concurrency) {
            Log.d(TAG, "Launching worker: " + i)
            var job = launch(Dispatchers.IO) {
                testUrls(i)
            }
            jobs.add(job)
        }

        Log.d(TAG, "Go coroutines...")

        jobs.joinAll()

        Log.d(TAG, "EnvoyConnectWorker is done")
    }

    override suspend fun doWork(): Result {
        // reset things
        envoyTests.clear()
        jobs.clear()

        // test direct connection first
        if (EnvoyConnectionTests.directUrl != "") {
            // testUrls.add(EnvoyNetworking.directUrl)
            val test = EnvoyTest(ENVOY_PROXY_DIRECT, EnvoyConnectionTests.directUrl)
            envoyTests.add(test)
        }
        // shuffle the rest of the URLs
        envoyTests.addAll(EnvoyConnectionTests.envoyTests.shuffled())

        Log.i(TAG, "EnvoyConnectWorker starting with "
                + envoyTests.size
                + " URLs to test")

        startWorkers()
        return Result.success()
    }
}