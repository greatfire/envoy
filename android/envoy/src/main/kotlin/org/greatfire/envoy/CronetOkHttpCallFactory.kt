// used in the Demo app

package org.greatfire.envoy

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.chromium.net.CronetEngine
import org.greatfire.envoy.CronetNetworking.cronetEngine

class CronetOkHttpCallFactory(private val client: OkHttpClient) : Call.Factory {
    override fun newCall(request: Request): Call {
        val engine = cronetEngine()
        return engine?.let { CronetOkHttpCall(client, it, request) } ?: client.newCall(request)
    }

    fun newCall(request: Request, engine: CronetEngine): Call {
        return CronetOkHttpCall(client, engine, request)
    }
}
