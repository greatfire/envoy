package org.greatfire.envoy

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.File
import org.json.JSONObject

/*
    THIS DOESN'T WORK 😔

    SELinux blocks it for reasons I don't understand:
    type=1400 audit(0.0:10695): avc:  denied  { search } for  name="tests" dev="dm-44" ino=65540 scontext=u:r:untrusted_app:s0:c207,c256,c512,c768 tcontext=u:object_r:shell_test_data_file:s0 tclass=dir permissive=0 app=org.greatfire.wikiunblocked
    but doing (AFAIKT) the exact same thing in the old ShadowsocksService
    works.. so we're using that (for now?)
*/

class EnvoyShadowsocks(val url: String, val context: Context) {
    companion object {
        private const val TAG = "EnvoyShadowsocks"
        private const val LOCAL_ADDRESS = "127.0.0.1"
        // this is an arbitrary, random port number
        // our Go code tests the port is unused, should this be smarter?
        const val LOCAL_PORT = "25627"
    }

    private var currentProcess: Process? = null

    fun start(): String {
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

        // Write out the config file
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

        // get the binary path
        val nativeLibraryDir = context.getApplicationInfo().nativeLibraryDir
        val executableFile = File(nativeLibraryDir, "libsslocal.so")
        val executablePath = executableFile.absolutePath
        val cmdArgs = arrayOf(executablePath, "-c", configFile.absolutePath)

        Log.i(TAG, "🗣️ Starting Shadowsocks on port " + LOCAL_PORT)
        currentProcess = Runtime.getRuntime().exec(cmdArgs)
        Log.d(TAG, "🗣️ Shadowsocks started")

        return LOCAL_PORT
    }

    fun stop() {
        currentProcess?.let {
            Log.i(TAG, "Stopping Shadowsocks")
            it.destroy()
            currentProcess = null
        }
    }
}