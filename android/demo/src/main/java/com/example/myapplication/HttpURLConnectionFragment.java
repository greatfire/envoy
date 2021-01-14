package com.example.myapplication;

import android.os.AsyncTask;
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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.net.URL;
import java.util.Locale;

public class HttpURLConnectionFragment extends BaseFragment {
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

            new HttpURLConnectionFragment.HttpURLConnectionRequestTask(this).execute(mUrl, envoyUrl);
        });
    }

    static class HttpURLConnectionRequestTask extends AsyncTask<String, String, String> {
        private static final String TAG = "HttpURLConnFragment";
        private WeakReference<HttpURLConnectionFragment> activityReference;

        // only retain a weak reference to the activity
        HttpURLConnectionRequestTask(HttpURLConnectionFragment context) {
            activityReference = new WeakReference<>(context);
        }

        @Override
        protected String doInBackground(String... uri) {
            if (this.activityReference.get() == null) {
                return null;
            }

            HttpURLConnection con = null;
            BufferedReader in = null;
            String errorMsg;
            try {
                CronetEngine.Builder engineBuilder = new CronetEngine.Builder(activityReference.get().getActivity());
                engineBuilder.setEnvoyUrl(uri[1]);
                CronetEngine engine = engineBuilder.build();

                URL url = new URL(uri[0]);
                con = (HttpURLConnection) engine.openConnection(url);
                con.setRequestMethod("GET");
                // setConnectTimeout is not supported by CronetHttpURLConnection
                // con.setConnectTimeout(5000);
                con.setReadTimeout(5000);
                // Log.i(TAG, "HttpURLConnection headers: " + con.getHeaderFields().toString());
                in = new BufferedReader(new InputStreamReader(con.getInputStream()));
                StringBuilder content = new StringBuilder();
                String inputLine;
                while (((inputLine = in.readLine()) != null)) {
                    content.append(inputLine);
                }
                in.close();
                Log.d(TAG, "HttpURLConnection returns " + content.toString());

                final String bytesReceived = content.toString();
                final String msg = "Completed";
                activityReference.get().getActivity().runOnUiThread(() -> {
                    TextView msgTextView = activityReference.get().getActivity().findViewById(R.id.msgTextView);
                    msgTextView.setText(msg);
                    TextView resultTextView = activityReference.get().getActivity().findViewById(R.id.resultTextView);
                    resultTextView.setText(bytesReceived);
                });

                return bytesReceived;
            } catch (MalformedURLException e) {
                Log.e(TAG, "url malformed", e);
                errorMsg = e.getMessage();
            } catch (ProtocolException e) {
                Log.e(TAG, "invalid protocol", e);
                errorMsg = e.getMessage();
            } catch (IOException e) {
                Log.e(TAG, "io exception", e);
                errorMsg = e.getMessage();
            } finally {
                if (con != null) {
                    con.disconnect();
                }
                if (in != null) {
                    try {
                        in.close();
                    } catch (IOException e) {
                        Log.e(TAG, "Error closing stream", e);
                        errorMsg = e.getMessage();
                    }
                }
            }

            final String msg = String.format(Locale.US, "Failed with %s", errorMsg);
            activityReference.get().getActivity().runOnUiThread(() -> {
                TextView msgTextView = activityReference.get().getActivity().findViewById(R.id.msgTextView);
                msgTextView.setText(msg);
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
