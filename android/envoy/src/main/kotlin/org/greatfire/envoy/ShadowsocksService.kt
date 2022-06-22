package org.greatfire.envoy

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URI

class ShadowsocksService : Service() {
    // Binder given to clients
    private val binder: IBinder = LocalBinder()

    inner class LocalBinder : Binder() {
        // Return this instance of ShadowsocksService so clients can call public methods
        fun getService(): ShadowsocksService = this@ShadowsocksService
    }

    @SuppressLint("NewApi")
    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {

        // START_REDELIVER_INTENT: if this service's process is killed while it is started then it
        // will be scheduled for a restart and the last delivered Intent re-delivered to it again

        val config = toJson(intent.getStringExtra("org.greatfire.envoy.START_SS_LOCAL") ?: "")
                ?: JSONObject()
        val localAddress: String = (intent.getStringExtra("org.greatfire.envoy.START_SS_LOCAL.LOCAL_ADDRESS")
                ?: "127.0.0.1")
        config.put("local_address", localAddress)
        val localPort = intent.getIntExtra("org.greatfire.envoy.START_SS_LOCAL.LOCAL_PORT", 1080)
        config.put("local_port", localPort)
        val configFile = File(ContextCompat.getNoBackupFilesDir(this), "shadowsocks.conf")
        // val configFile = File("/data/local/tmp/shadowsocks-envoy.conf")
        configFile.writeText(config.toString())

        val nativeLibraryDir = applicationInfo.nativeLibraryDir
        val executableFile = File(nativeLibraryDir, "libsslocal.so")
        val executablePath = executableFile.absolutePath
        Runnable {
            val cmdArgs = arrayOf(executablePath, "-c", configFile.absolutePath)
            Log.i(TAG, """run ${cmdArgs.contentToString()}""")

            val broadcastIntent = Intent(SHADOWSOCKS_SERVICE_BROADCAST)

            try {
                currentProcess = Runtime.getRuntime().exec(cmdArgs)
                broadcastIntent.putExtra(SHADOWSOCKS_SERVICE_RESULT, SHADOWSOCKS_STARTED)
                broadcastIntent.putExtra("org.greatfire.envoy.SS_LOCAL_STARTED.LOCAL_ADDRESS", localAddress)
                broadcastIntent.putExtra("org.greatfire.envoy.SS_LOCAL_STARTED.LOCAL_PORT", localPort)
                LocalBroadcastManager.getInstance(this@ShadowsocksService).sendBroadcast(broadcastIntent)
            } catch (e: IOException) {
                Log.e(TAG, cmdArgs.contentToString(), e)
                broadcastIntent.putExtra(SHADOWSOCKS_SERVICE_RESULT, SHADOWSOCKS_ERROR)
                LocalBroadcastManager.getInstance(this@ShadowsocksService).sendBroadcast(broadcastIntent)
            }
        }.run()

        // return super.onStartCommand(intent, flags, startId);
        return START_REDELIVER_INTENT
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)

        Log.i(TAG, "stopping shadowsocks service")

        // kill process when application closes
        killProcess()

        // destroy the service
        stopSelf()
    }

    companion object {
        private const val TAG = "ShadowsocksService"

        const val SHADOWSOCKS_SERVICE_BROADCAST = "SHADOWSOCKS_SERVICE_BROADCAST"
        const val SHADOWSOCKS_SERVICE_RESULT = "SHADOWSOCKS_SERVICE_RESULT"
        const val SHADOWSOCKS_STARTED = 200
        const val SHADOWSOCKS_ERROR = -200

        private var currentProcess: Process? = null

        private val pattern =
                """(?i)ss://[-a-zA-Z0-9+&@#/%?=.~*'()|!:,;_\[\]]*[-a-zA-Z0-9+&@#/%=.~*'()|\[\]]""".toRegex()
        private val userInfoPattern = "^(.+?):(.*)$".toRegex()

        fun toJson(ssUri: String): JSONObject? {
            val data = pattern.find(ssUri)?.value
            val uri = data?.toUri() ?: return null
            val match = userInfoPattern.matchEntire(String(Base64.decode(uri.userInfo,
                    Base64.NO_PADDING or Base64.NO_WRAP or Base64.URL_SAFE)))

            if (match != null) {
                val method = match.groupValues[1]
                val password = match.groupValues[2]
                // https://issuetracker.google.com/code/p/android/issues/detail?id=192855
                val javaURI = URI(data)
                var host = javaURI.host ?: ""
                if (host.firstOrNull() == '[' && host.lastOrNull() == ']') {
                    host = host.substring(1, host.length - 1)
                }
                val remotePort = javaURI.port
                val name = uri.fragment ?: ""
                val plugin = uri.getQueryParameter("plugin")
                val pluginOpts = uri.getQueryParameter("plugin-opts")

                return JSONObject().apply {
                    put("server", host)
                    put("server_port", remotePort)
                    put("password", password)
                    put("method", method)
                    put("remarks", name)
                    put("plugin", plugin)
                    put("plugin_opts", pluginOpts)
                }
            } else {
                return null
            }
        }

        fun killProcess() {
            currentProcess?.let {
                Log.i(TAG, "stopping shadowsocks process")
                it.destroy()
                currentProcess = null
                return
            }
            Log.i(TAG, "no shadowsocks process")
        }
    }
}