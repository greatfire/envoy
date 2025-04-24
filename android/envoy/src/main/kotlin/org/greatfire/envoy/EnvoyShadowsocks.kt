package org.greatfire.envoy

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.File
import org.json.JSONObject

class EnvoyShadowsocks(val url: String, val context: Context) {
    companion object {
        private const val TAG = "EnvoyShadowsocks"
        private const val LOCAL_ADDRESS = "127.0.0.1"
        // this is an arbitrary, random port number
        // our Go code tests the port is unused, should this be smarter?
        const val LOCAL_PORT = "25627"
    }

    private var currentProcess: Process? = null

    private fun startShadowSocks(): String {
        // The current Shadowsocks docs say the whole URL should be base64
        // encoded (outside of any fragment), but we've only been encoding
        // the user info part. I don't know if that's an old convention,
        // but we're staying consistent with past behavior for now
        var parsed: Uri? = null
        try {
            parsed = Uri.parse(url)
        } catch (e: Error) {
            Log.e(TAG, "Error parsing shadowsocks URL: ", e)
            return ""
        }

        val userInfo = String(Base64.decode(parsed.getUserInfo(),
            Base64.NO_PADDING or Base64.NO_WRAP or Base64.URL_SAFE))
        val parts = userInfo.split(':')
        val method = parts[0]
        val password = parts[1]

        val json = JSONObject().apply {
            put("server", parsed.getHost())
            put("server_port", parsed.getPort())
            put("method", method)
            put("password", password)
            put("local_address", LOCAL_ADDRESS)
            put("local_port", LOCAL_PORT)
        }

        val configFile = File(ContextCompat.getNoBackupFilesDir(context), "shadowsocks.conf")
        configFile.writeText(json.toString())

        val nativeLibraryDir = context.getApplicationInfo().nativeLibraryDir
        val executableFile = File(nativeLibraryDir, "libsslocal.so")
        val executablePath = executableFile.absolutePath
        val cmdArgs = arrayOf(executablePath, "-c", configFile.absolutePath)

        // launch shadowsocks in its own thread
        Runnable {
            Log.i(TAG, "Starting Shadowsocks on port " + LOCAL_PORT)
            fun run() {
                currentProcess = Runtime.getRuntime().exec(cmdArgs)
            }
        }.run()

        return LOCAL_PORT
    }

    fun start(): String {
        return startShadowSocks()
    }


    fun stop() {
        currentProcess?.let {
            Log.i(TAG, "Stopping Shadowsocks")
            it.destroy()
            currentProcess = null
        }
        // does the Runnable just get GC'd?
    }
}