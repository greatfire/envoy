package org.greatfire.envoy.transport

import org.greatfire.envoy.EnvoyConnectionTests
import org.greatfire.envoy.EnvoyTransportType
import org.greatfire.envoy.ShadowsocksService

import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

import android.content.Context
import android.net.Uri

class ShadowsocksTransport(url: String) : Transport(EnvoyTransportType.SHADOWSOCKS, url) {

    override suspend fun startTest(context: Context): Boolean {
        Log.d(TAG, "Testing Shadowsocks " + this)
        val addr = startService()

        // this already has the socks5:// prefix, I guess
        // we're not consistent there :)
        proxyUrl = addr

        Log.d(TAG, "testing Shadowsocks $addr")

        val res = testStandardProxy(Uri.parse(addr), testResponseCode)
        // if (res == false) {
        //     test.stopService()
        // }
        return res
    }

    override suspend fun startService(): String {
        if (serviceRunning) {
            Log.e(TAG, "Tried to start Shadowsocks when it was already running")
            return ""
        }

        Log.d(TAG, "Starting service for Shadowsocks")

        serviceRunning = true

        // sadly this new code doesn't work, see the comments there
        //
        // shadowsocks = EnvoyShadowsocks(url, state.ctx!!)
        // // come on Kotlin, we just assigned it!
        // shadowsocks!!.start()

        // // block (coroutine friendly) until it's up
        // EnvoyConnectionTests.isItUpYet(
        //     "127.0.0.1", EnvoyShadowsocks.LOCAL_PORT.toInt())

        // return "socks5://127.0.0.1:${EnvoyShadowsocks.LOCAL_PORT}"

        val shadowsocksIntent = Intent(state.ctx!!, ShadowsocksService::class.java)
        shadowsocksIntent.putExtra("org.greatfire.envoy.START_SS_LOCAL", url)
        // XXX shouldn't this be background?
        ContextCompat.startForegroundService(state.ctx!!, shadowsocksIntent)

        EnvoyConnectionTests.isItUpYet(
            "127.0.0.1", 1080)

        Log.d(TAG, "Oldskool Shadowsocks started")
        return "socks5://127.0.0.1:1080"
    }

    override fun stopService() {
        // stop the associated service
        // this is called to stop unused services

        // commented out in original class
        // shadowsocks?.let { it.stop() }
        // state.ctx!!.stopService(shadowsocksIntent)
        Log.d(TAG, "No stop method for Shadowsocks")
    }
}
