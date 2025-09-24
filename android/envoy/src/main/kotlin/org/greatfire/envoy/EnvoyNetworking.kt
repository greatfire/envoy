package org.greatfire.envoy

import android.content.Context
import android.util.Log
import androidx.work.*
import com.example.httpsigauth.common.crypto.CryptoWrapper
import com.example.httpsigauth.common.crypto.SupportedEncodingFormat
import com.example.httpsigauth.common.crypto.SupportedKeyType
import com.example.httpsigauth.exception.CryptoException
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.conscrypt.Conscrypt
import org.greatfire.envoy.transport.Transport
import java.security.Security

/*
    This object provides an external interface for setting up network connections with Envoy.
    It can also be accessed from the EnvoyConnectWorker and the EnvoyInterceptor.
*/

class EnvoyNetworking {

    companion object {
        private const val TAG = "EnvoyNetworking"

        // Should the Interceptor enable a direct connection if the app
        // requests appear to be working (i.e. returning 200 codes)
        var passivelyTestDirect = true
        var initialized = false

        fun initConcealedAuth() {
            Security.removeProvider("BC")
            Security.removeProvider("Conscryptprovider")

            val bouncyCastleProvider: BouncyCastleProvider = BouncyCastleProvider()
            val conscryptProvider = Conscrypt.newProviderBuilder()
                .setName("Conscryptprovider")
                .provideTrustManager(false)
                .defaultTlsProtocol("TLSv1.3").build()

            Security.insertProviderAt(conscryptProvider, 1)
            Security.insertProviderAt(bouncyCastleProvider, 2)
        }

        @JvmStatic
        fun init() {
            initConcealedAuth()
        }
    }

    private val state = EnvoyState.getInstance()

    // Public functions, this is the primary public interface for Envoy
    fun addEnvoyUrl(url: String): EnvoyNetworking {
        EnvoyConnectionTests.addEnvoyUrl(url)

        // let Java callers chain
        return this
    }

    // use a custom test URL and response code
    // default is ("https://www.google.com/generate_204", 204)
    fun setTestUrl(url: String, responseCode: Int): EnvoyNetworking {
        Transport.testUrl = url
        Transport.testResponseCode = responseCode

        return this
    }

    // Set the direct URL to the site, if this one works, Envoy is bypassed
    //
    // The caller should either pass a URL here or set passivelyTestDirect
    // direct to true. It's not a problem to do both, but probably not
    // necessary. Doing neither will disable direct connections
    fun setDirectUrl(newVal: String): EnvoyNetworking {
        EnvoyConnectionTests.addDirectUrl(newVal)

        return this
    }

    // Set the callback for reporting status to the main application
    fun setCallback(callback: EnvoyTestCallback): EnvoyNetworking {
        state.callback = callback
        return this
    }

    // Provide a context reference from the main application
    fun setContext(context: Context): EnvoyNetworking {
        state.ctx = context
        return this
    }

    fun setConcurrency(concurrency: Int): EnvoyNetworking {
        state.concurrency = concurrency
        return this
    }

    fun setBackoff(backoffEnabled: Boolean): EnvoyNetworking {
        state.backoffEnabled = backoffEnabled
        return this
    }

    fun setTestAllUrls(testAllUrls: Boolean): EnvoyNetworking {
        state.testAllUrls = testAllUrls
        return this
    }

    // XXX assumed to be Ed25519 keys (for now)
    //
    // userID: this is the identifier for the key passed to the server
    // Keys should be PKCS#1 or PKCS#8 encoded PEM
    fun configureConcealedaAuth(userID: String, publicKey: String, privateKey: String): EnvoyNetworking {

        try {
            state.concealedAuthPublicKey = CryptoWrapper.toSpecBytes(
                SupportedKeyType.Ed25519,
                SupportedEncodingFormat.PEM,
                publicKey)
            state.concealedAuthPrivateKey = CryptoWrapper.loadPrivateKey(
                SupportedKeyType.Ed25519,
                SupportedEncodingFormat.PEM,
                privateKey)
            // set this last, since we test this one and assume the others
            // are set if it is
            state.concealedAuthUser = userID
        } catch (e: CryptoException) {
            Log.e(TAG, "Failed to parse Concealed Auth keys")
        }
        return this
    }

    fun DEBUGsetTimeoutDirect(enabled: Boolean): EnvoyNetworking {
        state.debugTimeoutDriect = enabled
        return this
    }

    fun connect(): EnvoyNetworking {
        initialized = true
        Log.d(TAG, "🏄‍♂️🏄‍♂️🏄‍♂️ Starting Envoy connect...")

        val workRequest = OneTimeWorkRequestBuilder<EnvoyConnectWorker>()
            // connecting to the proxy is a high priority task
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()

        WorkManager
            .getInstance(state.ctx!!)
            .enqueue(workRequest)

        return this
    }
}