package com.example.myapplication;

import android.content.Context;
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

import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.toolbox.HurlStack;
import com.android.volley.toolbox.RequestFuture;
import com.android.volley.toolbox.StringRequest;
import com.android.volley.toolbox.Volley;

import org.chromium.net.CronetEngine;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.ExecutionException;

public class VolleyFragment extends BaseFragment {
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

            new VolleyFragment.VolleyRequestTask(new WeakReference<>(this)).execute(mUrl, envoyUrl);
        });
    }

    static class CronetStack extends HurlStack {
        private final CronetEngine engine;

        public CronetStack(Context context) {
            this("", context);
        }

        CronetStack(String envoyUrl, Context context) {
            CronetEngine.Builder engineBuilder = new CronetEngine.Builder(context);
            if (envoyUrl != null && !envoyUrl.isEmpty()) {
                //engineBuilder.setEnvoyUrl(envoyUrl);
            }
            engine = engineBuilder.build();
        }

        @Override
        protected HttpURLConnection createConnection(URL url) throws IOException {
            return (HttpURLConnection) engine.openConnection(url);
        }
    }

    static class VolleyRequestTask extends AsyncTask<String, String, String> {
        private static final String TAG = "VolleyFragment";
        private final WeakReference<VolleyFragment> activityReference;

        VolleyRequestTask(WeakReference<VolleyFragment> activityReference) {
            this.activityReference = activityReference;
        }

        @Override
        protected String doInBackground(String... uri) {
            if (this.activityReference.get() == null) {
                return null;
            }

            RequestQueue queue = Volley.newRequestQueue(activityReference.get().getActivity(), new CronetStack(uri[1], activityReference.get().getActivity()));
            StringRequest stringRequest = new StringRequest(Request.Method.GET, uri[0],
                    response -> Log.d(TAG, "volley returns " + response),
                    error -> Log.e(TAG, "volley error:", error));
            queue.add(stringRequest);

            RequestFuture<String> future = RequestFuture.newFuture();
            StringRequest syncStringRequest = new StringRequest(Request.Method.GET, uri[0], future, future);
            queue.add(syncStringRequest);

            try {
                String response = future.get(); // this will block forever
                Log.d(TAG, "volley(sync) returns " + response);
                final String bytesReceived = future.get();
                final String msg = "Completed";
                this.activityReference.get().getActivity().runOnUiThread(() -> {
                    TextView msgTextView = activityReference.get().getActivity().findViewById(R.id.msgTextView);
                    msgTextView.setText(msg);
                    TextView resultTextView = activityReference.get().getActivity().findViewById(R.id.resultTextView);
                    resultTextView.setText(bytesReceived);
                });
                return bytesReceived;
            } catch (InterruptedException | ExecutionException e) {
                Log.e(TAG, "volley(sync) error:", e);
                final String msg = String.format(Locale.US, "Failed with %s", e.getMessage());
                activityReference.get().getActivity().runOnUiThread(() -> {
                    TextView msgTextView = activityReference.get().getActivity().findViewById(R.id.msgTextView);
                    msgTextView.setText(msg);
                });
            }

            return null;
        }

        @Override
        protected void onPostExecute(String result) {
            super.onPostExecute(result);
            //Do anything with response..
        }
    }
}
