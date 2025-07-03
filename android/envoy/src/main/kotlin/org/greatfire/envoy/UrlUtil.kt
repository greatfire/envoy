package org.greatfire.envoy

import android.net.Uri
import android.util.Log

// This is a helper class to try to sanitize URLs, Hostnames, and Excptions
//
// Envoy hosts and URLs may be sensative, so we don't want to log them

class UrlUtil {

    companion object {

        private val TAG = "UrlUtil"

        // Try to make a logging safe hostname. Envoy hostnames may be
        // considered private, so we don't want to log them
        @JvmStatic
        fun sanitizeHostname(host: String): String {
            if (host.isNullOrEmpty()) {
                return "???Empty?"
            }

            val parts = host.split(".").toMutableList()

            when {
                parts.size == 1 -> return "???"
                parts.size == 2 -> return "${parts[0]}.???"
                parts.size >= 3 -> {
                    // there's some problem with removeFrist/removeLast
                    // I lost the link with my first try at this code :)
                    parts.removeAt(0)
                    parts.removeAt(parts.lastIndex)
                    val temp = parts.joinToString(".")
                    return "???.$temp.???"
                }
                else -> return "???Error"
            }
        }

        // Try to make a URL safe for logging
        //
        // Hysteria2 and Shadowsocks have their auth info in the auth field
        // of the URL, V2Ray has it is GET params
        // Envoy proxy URLs may have a private path
        // remove all of the above
        //
        @JvmStatic
        fun sanitizeUrl(url: String): String {
            if (url.isNullOrEmpty()) {
                return "Empty???"
            }

            // scheme should be the first part
            if (url.contains("://")) {
                val parts = url.split("://", limit=2)
                val scheme = parts[0]
                // if we have another part, it's a hostname
                var host = "?Empty?"
                if (parts.size > 1) {
                    host = parts[1].split("/", limit=2)[0]
                    host = sanitizeHostname(host)
                }

                return "$scheme://$host/…"
            }

            return "?Invalid??"
        }

        @JvmStatic
        fun sanitizeException(e: Exception): String {

            val eString = "" + e
            return sanitizeEString(eString)
        }

        @JvmStatic
        fun sanitizeError(e: Error): String {

            val eString = "" + e
            return sanitizeEString(eString)
        }

        fun sanitizeEString(eString: String): String {

            val eParts = eString.split(" ")
            var newString = ""

            eParts.forEach { part ->
                if (newString.length > 0) {
                    newString = newString + " "
                }
                if (part.contains("://")) {
                    newString = newString + sanitizeUrl(part)
                } else {
                    newString = newString + part
                }
            }

            return newString
        }
    }
}
