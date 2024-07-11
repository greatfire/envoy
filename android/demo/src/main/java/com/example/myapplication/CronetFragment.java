package com.example.myapplication;

import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.chromium.net.CronetEngine;
import org.chromium.net.CronetException;
import org.chromium.net.UrlRequest;
import org.chromium.net.UrlResponseInfo;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class CronetFragment extends BaseFragment {
    private TextView mResultTextView;
    private TextView mMsgTextView;
    private String mUrl;

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        final Button loadButton = view.findViewById(R.id.loadButton);
        final Spinner httpMethodSpinner = view.findViewById(R.id.httpMethodSpinner);
        final EditText urlEditText = view.findViewById(R.id.urlEditText);
        final EditText detailEditText = view.findViewById(R.id.detailEditText);
        final EditText envoyUrlEditText = view.findViewById(R.id.envoyUrlEditText);
        mResultTextView = view.findViewById(R.id.resultTextView);
        mMsgTextView = view.findViewById(R.id.msgTextView);

        loadButton.setOnClickListener(v -> {
            mMsgTextView.setText(getString(R.string.begin_request_msg, urlEditText.getText().toString()));
            mUrl = urlEditText.getText().toString();
            String envoyUrl = envoyUrlEditText.getText().toString();

            CronetEngine.Builder engineBuilder = new CronetEngine.Builder(view.getContext());
            engineBuilder.setUserAgent("curl/7.66.0");
            engineBuilder.setEnvoyUrl(envoyUrl);

            CronetEngine engine = engineBuilder.build();
            //Log.d(TAG, "engine version " + engine.getVersionString());
            //File outputFile = File.createTempFile("cronet", "log",
            //    Environment.getExternalStorageDirectory());
            //engine.startNetLogToFile(outputFile.toString(), false);
            Executor executor = Executors.newSingleThreadExecutor();
            MyUrlRequestCallback callback = new MyUrlRequestCallback();
            UrlRequest.Builder requestBuilder = engine.newUrlRequestBuilder(
                    mUrl, callback, executor);
            UrlRequest request = requestBuilder.addHeader("My-Header1", "value1").build();
            request.start();
            // https://stackoverflow.com/questions/41110684/cronet-and-experimentalcronetengine
            //CronetURLStreamHandlerFactory cronetURLStreamHandlerFactory =
            //    new CronetURLStreamHandlerFactory((ExperimentalCronetEngine)engine);
            //URL.setURLStreamHandlerFactory(cronetURLStreamHandlerFactory);
        });
    }

    // Example UrlRequest.Callback
    class MyUrlRequestCallback extends UrlRequest.Callback {

        private static final String TAG = "MyUrlRequestCallback";
        private final ByteArrayOutputStream mBytesReceived = new ByteArrayOutputStream();
        private final WritableByteChannel mReceiveChannel = Channels.newChannel(mBytesReceived);
        private long mStart;

        @Override
        public void onRedirectReceived(UrlRequest request,
                                       UrlResponseInfo responseInfo, String newLocationUrl) {
            Log.d(TAG, "onRedirectReceived");
            request.followRedirect();
        }

        @Override
        public void onResponseStarted(UrlRequest request,
                                      UrlResponseInfo responseInfo) {
            Log.d(TAG, "onResponseStarted, headers: " + responseInfo.getAllHeaders());
            mStart = System.nanoTime();
            request.read(ByteBuffer.allocateDirect(32 * 1024));
        }

        @Override
        public void onReadCompleted(UrlRequest request,
                                    UrlResponseInfo responseInfo, ByteBuffer byteBuffer) {
            Log.d(TAG, "onReadCompleted: " + byteBuffer);
            byteBuffer.flip();
            try {
                mReceiveChannel.write(byteBuffer);
            } catch (IOException e) {
                Log.e(TAG, "IOException during ByteBuffer read: ", e);
            }
            byteBuffer.clear();
            request.read(byteBuffer);
        }

        @Override
        public void onSucceeded(UrlRequest request,
                                UrlResponseInfo responseInfo) {
            long stop = System.nanoTime();

            final String bytesReceived = mBytesReceived.toString();
            final String msg = String.format(Locale.US,
                    "Completed with latency=%d ns, status=%d", stop - mStart, responseInfo.getHttpStatusCode());
            CronetFragment.this.getActivity().runOnUiThread(() -> {
                mMsgTextView.setText(msg);
                mResultTextView.setText(bytesReceived);
            });
        }

        @Override
        public void onFailed(UrlRequest request,
                             UrlResponseInfo responseInfo, CronetException error) {
            // responseInfo might be null.
            Log.e(TAG, "onFailed: ", error);

            final String msg = String.format(Locale.US, "Failed with %s", error.getMessage());
            CronetFragment.this.getActivity().runOnUiThread(() -> mMsgTextView.setText(msg));
        }
    }
}
