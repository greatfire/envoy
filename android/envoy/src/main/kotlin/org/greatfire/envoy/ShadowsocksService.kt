package org.greatfire.envoy

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
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
        val channelId = "shadowsocks-channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "shadowsocks-channel"
            val channel = NotificationChannel(
                    channelId, name, NotificationManager.IMPORTANCE_LOW)
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
        // val notificationIntent = Intent(this, MainActivity::class.java)
        // val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0)
        @Suppress("DEPRECATION")
        val notification: Notification = Notification.Builder(this, channelId)
                .setAutoCancel(false)
                .setOngoing(true)
                .setContentTitle("Shadowsocks in running")
                .setContentText("Shadowsocks in running")
                // .setSmallIcon(R.drawable.ic_notification)
                // .setContentIntent(pendingIntent)
                // deprecated in API level 26, see NotificationChannel#setImportance(int)
                .setPriority(Notification.PRIORITY_LOW)
                .setTicker("Shadowsocks in running")
                .build()
        startForeground(SystemClock.uptimeMillis().toInt(), notification)

        val nativeLibraryDir = applicationInfo.nativeLibraryDir
        val executableFile = File(nativeLibraryDir, "libsslocal.so")
        val executablePath = executableFile.absolutePath
        Runnable {
            val cmdArgs = arrayOf(executablePath, "-c", configFile.absolutePath)
            Log.i(TAG, """run ${cmdArgs.contentToString()}""")
            try {
                Runtime.getRuntime().exec(cmdArgs)
                val broadcastIntent = Intent()
                broadcastIntent.action = "com.greatfire.envoy.SS_LOCAL_STARTED"
                broadcastIntent.putExtra("org.greatfire.envoy.SS_LOCAL_STARTED.LOCAL_ADDRESS", localAddress)
                broadcastIntent.putExtra("org.greatfire.envoy.SS_LOCAL_STARTED.LOCAL_PORT", localPort)
                sendBroadcast(broadcastIntent)
            } catch (e: IOException) {
                Log.e(TAG, cmdArgs.contentToString(), e)
            }
        }.run()

        // return super.onStartCommand(intent, flags, startId);
        return START_REDELIVER_INTENT
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    companion object {
        private const val TAG = "ShadowsocksService"

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
    }
}