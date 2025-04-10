// used in the Demo app

package org.greatfire.envoy

import okhttp3.JavaNetCookieJar
import okhttp3.OkHttpClient
import java.net.CookieManager

object CronetOkHttpConnectionFactory {
    // private const val CACHE_DIR_NAME = "okhttp-cache"
    // private const val NET_CACHE_SIZE = (64 * 1024 * 1024).toLong()

    @JvmStatic
    val client = createClient()

    private fun createClient(): OkHttpClient {
        //val cache = Cache(File(context.cacheDir, CACHE_DIR_NAME), NET_CACHE_SIZE)
        val cookieJar = JavaNetCookieJar(CookieManager())
        return OkHttpClient.Builder()
                .cookieJar(cookieJar)
                //.cache(cache)
                .addInterceptor(CronetInterceptor())
                .build()
    }
}