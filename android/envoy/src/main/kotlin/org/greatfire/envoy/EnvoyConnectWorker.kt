package org.greatfire.envoy

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.net.URL
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

    private val testUrls = ArrayDeque(listOf<String>(""))
    private val jobs = mutableListOf<Job>()

    // This is run in EnvoyNetworking.concurrency number of coroutines
    // It effectively limits the number of servers we test at a time
    suspend fun testUrl(id: Int) {
        var url = testUrls.removeFirstOrNull()

        val WTAG = TAG + "-" + id

        Log.d(WTAG, "worker: " + id)
        Log.d(WTAG, "Thread: " + Thread.currentThread().name)

        while (!url.isNullOrEmpty()) {
            val proxyUrl = URL(url)
            Log.d(WTAG, "Testing URL: " + url)

           val res = when (proxyUrl.getProtocol()) {
                // "direct" -> {
                //     val result = EnvoyNetworking.testDirectConnection()
                //     Log.d(WTAG, "Direct connection worked? " + result)
                //     EnvoyNetworking.setDirect(result)
                //     result
                // }
                "http", "https" -> {
                    // XXX assume this is an Envoy proxy for now
                    Log.d(WTAG, "Testing Envoy URL")
                    EnvoyNetworking.testEnvoyOkHttp(proxyUrl)
                }
                "socks5" -> {
                    Log.d(WTAG, "Testing SOCKS5")
                    EnvoyNetworking.testStandardProxy(proxyUrl)
                }
                else -> {
                    Log.e(WTAG, "Unsupported protocol: " + proxyUrl.getProtocol())
                    false
                }
            }

            Log.d(WTAG, "URL: " + url + " worked?: " + res)
            url = testUrls.removeFirstOrNull()
        }

        Log.d(WTAG, "testUrl " + id + " is out of URLs")
    }

    private fun startWorkers() = runBlocking {
        for (i in 1..EnvoyNetworking.concurrency) {
            Log.d(TAG, "Launching worker: " + i)
            var job = launch(Dispatchers.IO) {
                testUrl(i)
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
        testUrls.clear()
        jobs.clear()

        // test direct connection first
        if (EnvoyNetworking.directUrl != "") {
            testUrls.add(EnvoyNetworking.directUrl)
        }
        // shuffle the rest of the URLs
        testUrls.addAll(EnvoyNetworking.envoyUrls.shuffled())

        Log.i(TAG, "EnvoyConnectWorker starting with "
                + testUrls.size
                + " URLs to test")

        startWorkers()
        return Result.success()
    }
}