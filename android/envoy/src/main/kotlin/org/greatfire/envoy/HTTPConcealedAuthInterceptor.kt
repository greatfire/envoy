package org.greatfire.envoy

import com.example.httpsigauth.common.SignatureHTTPAuthentication.signRequest
import com.example.httpsigauth.common.SignatureScheme

import android.util.Log
import java.security.PrivateKey
import javax.net.ssl.SSLSocket
import okhttp3.Interceptor
import okhttp3.Response
import org.conscrypt.Conscrypt


class HTTPConcealedAuthInterceptor(
        val user: String,
        val publicKey: ByteArray,
        val privateKey: PrivateKey,
    ): Interceptor {

    companion object {
        private const val TAG = "Envoy - HTTPCAInterceptor"
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val connection = chain.connection()
        if (connection != null && Conscrypt.isConscrypt(connection.socket() as SSLSocket)) {
            val socket = connection.socket() as SSLSocket
            val headerValue = signRequest(
                socket,
                originalRequest.url.scheme,
                originalRequest.url.host,
                originalRequest.url.port,
                "",
                user.toByteArray(),
                publicKey,
                privateKey,
                SignatureScheme.ed25519
            )

            Log.d(TAG, "🎸 Authorization: $headerValue")
            val newRequest = originalRequest.newBuilder()
                .header("Authorization", headerValue)
                .build()

            return chain.proceed(newRequest)
        }
        else if (connection == null) {
            Log.w(TAG, "☹️ connection is null")
        } else {
            Log.w(TAG, "☹️ not conscrypt? " + connection.socket())
        }
        return chain.proceed(originalRequest)
    }
}