
package org.greatfire.envoy

import android.util.Log
import kotlinx.coroutines.*
import tech.relaycorp.doh.DoHClient


class EnvoyDns() {

    companion object {
        private const val TAG = "EnvoyDns"

        private val CLOUDFLARE_SERVERS = listOf<String>("1.1.1.1", "1.0.0.1", "[2606:4700:4700::1111]", "[2606:4700:4700::1001]")
        private val QUAD9_SERVERS = listOf<String>("9.9.9.9", "149.112.112.112", "[2620:fe::fe]", "[2620:fe::fe:9]")
        private val DNS_SB_SERVERS = listOf<String>("185.222.222.222", "45.11.45.11", "[2a09::]", "[2a11::]")

        private val SERVERS = listOf<String>("45.11.45.11", "[2a09::]")
    }

    var chosenServer: String? = ""
    var serverUrl: String? = ""

    // Make test query to a random host name from this list
    // so the DNS providers and censors see a little variety
    private val HOSTS_TO_RESOLVE = ArrayDeque<String>(
        listOf("greatfire.org", "guardianproject.info", "wikipedia.org",
            "ietf.org", "baidu.com", "google.com", "facebook.com",
            "youtube.com", "instragram.com").shuffled())

    suspend fun testServer(host: String): Boolean {
        val dnsUrl = "https://$host/dns-query"

        var aRecords : List<String>? = null
        val doh = DoHClient(dnsUrl)
        val resolveHost = HOSTS_TO_RESOLVE.removeFirst()
        HOSTS_TO_RESOLVE.add(resolveHost) // add it back to use later
        doh.use {
            try {
                aRecords = doh.lookUp(resolveHost, "A").data
                // Log.d(TAG, "aRecords: " + aRecords)
                // XXX should we test the list isn't empty at least
                // or seomthing?
                // We assume a return here is a success
                if (chosenServer == "") {
                    Log.i(TAG, "picking DNS server: " + host)
                    chosenServer = host
                    serverUrl = dnsUrl
                } else {
                    Log.d(TAG, "server $host works, but we already have one")
                }
                return true
            } catch (e: Error) {
                Log.d(TAG, "lookup failed $host: " + e)
                return false
            }
        }
    }

    suspend fun pickAServer() = coroutineScope {
        val serverList = listOf(
            CLOUDFLARE_SERVERS,
            QUAD9_SERVERS,
            DNS_SB_SERVERS
        ).flatten()

        // Pick a few random servers to try
        val workList = serverList.shuffled().subList(0, 3)
        val jobs = mutableListOf<Job>()

        workList.forEach {
            val job = launch {
                testServer(it)
            }
            jobs.add(job)
        }
        jobs.joinAll()

        // XXX try some more here?
        if (chosenServer == "") {
            Log.e(TAG, "Failed to find a working DoH server")
        }
    }

    suspend fun getECHConfig(host: String): String {
        var echRecord: String? = null

        // wait for a working server to be found
        // this is hacky, and should have a timeout XXX
        while (serverUrl == "") {
            Log.e(TAG, "getECHConfig called when serverUrl is null")
            delay(100)
        }

        Log.d(TAG, "looking up HTTPS recored for $host at $serverUrl")

        // should we cache this client? We only use it once
        // per Envoy URL for now
        val doh = DoHClient(serverUrl!!)
        doh.use {
            try {
                val ans = doh.lookUp(host, "HTTPS").data
                echRecord = ans[0]
                Log.d(TAG, "ECH: $echRecord")
            } catch (e: Error) {
                Log.e(TAG, "ECH Config lookup failed for $host $e")
            }
        }
        return echRecord!!
    }

    suspend fun init() {
        pickAServer()
    }
}
