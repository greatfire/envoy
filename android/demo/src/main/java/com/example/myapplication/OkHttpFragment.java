package com.example.myapplication;

import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

import org.chromium.net.CronetEngine;
import org.greatfire.envoy.CronetInterceptor;
import org.greatfire.envoy.CronetOkHttpCallFactory;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.Locale;
import java.util.Objects;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;

public class OkHttpFragment extends BaseFragment {

    private TextView mResultTextView;
    private TextView mMsgTextView;
    private String mUrl;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_base, container, false);

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

            new OkHttpRequestTask(this).execute(mUrl, envoyUrl);
        });
        return view;
    }

    static class OkHttpRequestTask extends AsyncTask<String, String, String> {
        private static final String TAG = "OkHttpFragment";
        private WeakReference<OkHttpFragment> activityReference;

        // only retain a weak reference to the activity
        OkHttpRequestTask(OkHttpFragment context) {
            activityReference = new WeakReference<>(context);
        }

        @Override
        protected String doInBackground(String... uri) {
            if (this.activityReference.get() == null) {
                return null;
            }

            CronetEngine.Builder engineBuilder = new CronetEngine.Builder(activityReference.get().getActivity());
            engineBuilder.setEnvoyUrl(uri[1]);

            CronetEngine engine = engineBuilder.build();

            OkHttpClient client = new OkHttpClient.Builder()
                    .addInterceptor(new CronetInterceptor(engine))
                    .build();
            // OkHttpClient client = CronetOkHttpConnectionFactory.getClient()
            okhttp3.Request request = new okhttp3.Request.Builder().url(uri[0]).build();

            try (okhttp3.Response response = client.newCall(request).execute()) {
                // Log.i(TAG, "okhttp headers: " + response.headers());
                String responseString = Objects.requireNonNull(response.body()).string();
                Log.d(TAG, "okhttp returns " + responseString);
            } catch (IOException e) {
                Log.e(TAG, "okhttp error:", e);
            }

            okhttp3.Request request2 = new okhttp3.Request.Builder().url(uri[0]).build();
            CronetOkHttpCallFactory factory = new CronetOkHttpCallFactory(client);
            try (okhttp3.Response response = factory.newCall(request2).execute()) {
                String responseString = Objects.requireNonNull(response.body()).string();
                Log.d(TAG, "okhttp(factory) returns " + responseString);
            } catch (IOException e) {
                Log.e(TAG, "okhttp(factory) error:", e);
            }

            okhttp3.Request asyncRequest = new okhttp3.Request.Builder().url(uri[0]).build();
            client.newCall(asyncRequest).enqueue(new Callback() {
                @Override
                public void onFailure(@NotNull Call call, @NotNull IOException e) {
                    Log.e(TAG, "okhttp(async) error:", e);
                    final String msg = String.format(Locale.US, "Failed with %s", e.getMessage());
                    activityReference.get().getActivity().runOnUiThread(() -> {
                        TextView msgTextView = activityReference.get().getActivity().findViewById(R.id.msgTextView);
                        msgTextView.setText(msg);
                    });
                }

                @Override
                public void onResponse(@NotNull Call call, @NotNull okhttp3.Response response) throws IOException {
                    // Log.d(TAG, "okhttp(async) headers: " + response.headers());

                    final String bytesReceived = Objects.requireNonNull(response.body()).string();
                    final String msg = String.format(Locale.US, "Completed with  status=%d", response.code());
                    activityReference.get().getActivity().runOnUiThread(() -> {
                        TextView msgTextView = activityReference.get().getActivity().findViewById(R.id.msgTextView);
                        msgTextView.setText(msg);
                        TextView resultTextView = activityReference.get().getActivity().findViewById(R.id.resultTextView);
                        resultTextView.setText(bytesReceived);
                    });
                }
            });

            return null;
        }

        @Override
        protected void onPostExecute(String result) {
            super.onPostExecute(result);
            //Do anything with response..
        }
    }
}

