package org.greatfire.envoy

import android.os.ConditionVariable
import android.util.Log
import okhttp3.Call
import okhttp3.Callback
import okhttp3.EventListener
import okhttp3.Headers
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.chromium.net.CronetException
import org.chromium.net.UrlRequest
import org.chromium.net.UrlResponseInfo
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.lang.IllegalArgumentException
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.util.*

class CronetUrlRequestCallback @JvmOverloads internal constructor(
        private val mOriginalRequest: Request,
        private val mCall: Call,
        private val mEventListener: EventListener? = null,
        private val mResponseCallback: Callback? = null) : UrlRequest.Callback() {

    private var mRedirectCount = 0
    private var mResponse: Response
    private var mIOException: IOException? = null
    private val mResponseConditionVariable = ConditionVariable()
    private val mReceivedByteArrayOutputStream = ByteArrayOutputStream()
    private val mReceiveChannel = Channels.newChannel(mReceivedByteArrayOutputStream)

    init {
        mResponse = Response.Builder()
                .sentRequestAtMillis(System.currentTimeMillis())
                .request(mOriginalRequest)
                .protocol(Protocol.HTTP_1_0)
                .code(0)
                .message("")
                .build()
    }

    @Throws(IOException::class)
    fun blockForResponse(): Response {
        mResponseConditionVariable.block()
        if (mIOException != null) {
            throw mIOException as IOException
        }
        return mResponse
    }

    override fun onRedirectReceived(request: UrlRequest, responseInfo: UrlResponseInfo, newLocationUrl: String) {
        if (mRedirectCount > MAX_FOLLOW_COUNT) {
            request.cancel()
        }
        mRedirectCount += 1
        val client = OkHttpClient.Builder().build()
        if (mOriginalRequest.url.isHttps && newLocationUrl.startsWith("http://") && client.followSslRedirects) {
            request.followRedirect()
        } else if (!mOriginalRequest.url.isHttps && newLocationUrl.startsWith("https://") && client.followSslRedirects) {
            request.followRedirect()
        } else if (client.followRedirects) {
            request.followRedirect()
        } else {
            request.cancel()
        }
    }

    override fun onResponseStarted(request: UrlRequest, responseInfo: UrlResponseInfo) {
        mResponse = toOkResponse(mResponse, responseInfo)
        mEventListener?.responseHeadersEnd(mCall, mResponse)
        mEventListener?.responseBodyStart(mCall)

        request.read(ByteBuffer.allocateDirect(32 * 1024))
    }

    @Throws(Exception::class)
    override fun onReadCompleted(request: UrlRequest, responseInfo: UrlResponseInfo, byteBuffer: ByteBuffer) {
        // flip to write mode
        byteBuffer.flip()
        try {
            mReceiveChannel.write(byteBuffer)
        } catch (e: IOException) {
            Log.e(TAG, "IOException onReadCompleted: ", e)
            throw e
        }
        byteBuffer.clear()
        request.read(byteBuffer)
    }

    override fun onSucceeded(request: UrlRequest, responseInfo: UrlResponseInfo) {
        mEventListener?.responseBodyEnd(mCall, responseInfo.receivedByteCount)

        // set the default value for empty content type?
        // also set ; charset="utf-8" ?
        val contentType = mResponse.header("content-type", "text/html")
        val mediaType: MediaType? = (contentType
                ?: """text/plain; charset="utf-8"""").toMediaTypeOrNull()
        val responseBody = mReceivedByteArrayOutputStream.toByteArray().toResponseBody(mediaType)
        val newRequest = mOriginalRequest.newBuilder()
                .url(responseInfo.url)
                .build()
        mResponse = mResponse.newBuilder()
                .body(responseBody)
                .request(newRequest).build()

        mResponseConditionVariable.open()
        mEventListener?.callEnd(mCall)
        try {
            mResponseCallback?.onResponse(mCall, mResponse)
        } catch (e: IOException) {
            Log.e(TAG, "Callback onResponse failed:", e)
        }
    }

    override fun onFailed(request: UrlRequest, info: UrlResponseInfo?, error: CronetException?) {
        val wrappedError = IOException("Cronet Exception Occurred", error)
        mIOException = wrappedError
        mResponseConditionVariable.open()
        mEventListener?.callFailed(mCall, wrappedError)
        mResponseCallback?.onFailure(mCall, wrappedError)
    }

    override fun onCanceled(request: UrlRequest, info: UrlResponseInfo?) {
        mResponseConditionVariable.open()
        mEventListener?.callEnd(mCall)
    }

    companion object {
        private const val TAG = "UrlRequestCallback"
        private const val MAX_FOLLOW_COUNT = 20
        private fun protocolFromNegotiatedProtocol(responseInfo: UrlResponseInfo): Protocol {
            val negotiatedProtocol = responseInfo.negotiatedProtocol.toLowerCase(Locale.ENGLISH)
            return when {
                negotiatedProtocol.contains("quic") -> {
                    Protocol.QUIC
                }
                negotiatedProtocol.contains("h2") -> {
                    Protocol.HTTP_2
                }
                negotiatedProtocol.contains("1.1") -> {
                    Protocol.HTTP_1_1
                }
                negotiatedProtocol.contains("spdy") -> {
                    Protocol.SPDY_3
                }
                else -> {
                    Protocol.HTTP_1_0
                }
            }
        }

        private fun toOkResponse(response: Response, responseInfo: UrlResponseInfo): Response {
            val protocol = protocolFromNegotiatedProtocol(responseInfo)
            val headers = toOkHeaders(responseInfo)
            return response.newBuilder()
                    .receivedResponseAtMillis(System.currentTimeMillis())
                    .protocol(protocol)
                    .code(responseInfo.httpStatusCode)
                    .message(responseInfo.httpStatusText)
                    .headers(headers)
                    .build()
        }

        private fun toOkHeaders(responseInfo: UrlResponseInfo): Headers {
            val headers = responseInfo.allHeadersAsList
            val headerBuilder = Headers.Builder()
            for ((key, value) in headers) {
                try {
                    // Strip all content encoding headers for decoding is handled by cronet
                    if (key.equals("content-encoding", ignoreCase = true)) {
                        continue
                    }
                    if (key == null) {
                        continue
                    }
                    headerBuilder.add(key, value)
                } catch (e: IllegalArgumentException) {
                    Log.e(TAG, "Invalid header, $key: $value", e)
                }
            }
            return headerBuilder.build()
        }
    }
}
