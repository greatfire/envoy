
package org.greatfire.envoy

import android.util.Log
import kotlinx.coroutines.*
import tech.relaycorp.doh.DoHClient
import tech.relaycorp.doh.LookupFailureException


class EnvoyDns() {

    companion object {
        private const val TAG = "EnvoyDns"

        private val CLOUDFLARE_SERVERS = listOf<String>(
            "1.1.1.1", "1.0.0.1",
            "[2606:4700:4700::1111]", "[2606:4700:4700::1001]")
        private val QUAD9_SERVERS = listOf<String>(
            "9.9.9.9", "149.112.112.112",
            "[2620:fe::fe]", "[2620:fe::fe:9]")
        private val DNS_SB_SERVERS = listOf<String>(
            "185.222.222.222", "45.11.45.11",
            "[2a09::]", "[2a11::]")
        private val SWITCH_CH_SERVERS = listOf<String>(
            "130.59.31.248", "130.59.31.251",
            "[2001:620:0:ff::2]", "[2001:620:0:ff::3]")
        private val YANDEX_SERVERS = listOf<String>(
            "77.88.8.8", "77.88.8.1", "[2a02:6b8::feed:0ff]",
            "[2a02:6b8:0:1::feed:0ff]")
        private val ADGUARD_SERVERS = listOf<String>(
            "94.140.14.141", "94.140.14.140",
            "[2a10:50c0::2:ff]", "[2a10:50c0::1:ff]")

        // Cloudflare is somewhat arbitrary, ipv6 seems less often blocked
        // in my brief testing
        // this is used if something goes wrong
        private const val FALLBACK_DNS_SERVER = "[2606:4700:4700::1111]"
    }

    var chosenServer: String? = null
    var serverUrl: String? = null

    var retries = 0
    val max_retries = 4

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
                // XXX should we test the list isn't empty at least
                // or seomthing?
                // We assume a return here is a success
                //
                // there's a race condition here, 2 servers can be chosen,
                // it's pretty harmess but should be fixed
                if (chosenServer.isNullOrEmpty()) {
                    Log.i(TAG, "picking DNS server: " + host)
                    chosenServer = host
                    serverUrl = dnsUrl
                } else {
                    Log.d(TAG, "server $host works, but we already have one")
                }
                return true
            } catch (e: Exception) {
                Log.d(TAG, "lookup failed $host: " + e)
                return false
            }
        }
    }

    suspend fun pickAServer() = coroutineScope {
        val serverList = listOf(
            CLOUDFLARE_SERVERS,
            QUAD9_SERVERS,
            DNS_SB_SERVERS,
            SWITCH_CH_SERVERS,
            YANDEX_SERVERS,
            ADGUARD_SERVERS,
        ).flatten()

        try {
            // Pick a few random servers to try
            val workList = serverList.shuffled().subList(0, 5)
            val jobs = mutableListOf<Job>()

            workList.forEach {
                val job = launch {
                    // timeout pretty quickly
                    withTimeoutOrNull(5000) {
                        testServer(it)
                    }
                }
                jobs.add(job)
            }
            jobs.joinAll()
        } catch (e: Exception) {
            Log.e(TAG, "Unhandled DNS error $e")
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
            } catch (e: Error) {
                Log.e(TAG, "ECH Config lookup failed for $host $e")
                // we're probably talking to cloudflare, so try their key
                return ""
            }
        }

        val re = Regex("ech=([^ ]+)")
        val matchResult = re.find(echRecord!!)
        val ech = matchResult?.groups[1]?.value

        return ech ?: ""
    }

    suspend fun init() {
        // Try a few times
        while (chosenServer.isNullOrEmpty() && retries <= max_retries) {
            Log.d(TAG, "try number $retries")
            pickAServer()
            retries++
        }

        // fall back to one of the servers, a last ditch effort
        if (chosenServer == "") {
            Log.e(TAG, "Failed to find a working DoH server, using default $FALLBACK_DNS_SERVER")
            chosenServer = FALLBACK_DNS_SERVER
        }

    }
}
