package com.example.myapplication;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.chromium.net.CronetEngine;
import org.greatfire.envoy.CronetWebViewClient;

public class WebViewFragment extends BaseFragment {
    private WebView mResultWebView;
    private TextView mMsgTextView;
    private String mUrl;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_web_view, container, false);
        final Button loadButton = view.findViewById(R.id.loadButton);
        final Spinner httpMethodSpinner = view.findViewById(R.id.httpMethodSpinner);
        final EditText urlEditText = view.findViewById(R.id.urlEditText);
        final EditText detailEditText = view.findViewById(R.id.detailEditText);
        final EditText envoyUrlEditText = view.findViewById(R.id.envoyUrlEditText);
        mResultWebView = view.findViewById(R.id.resultWebView);
        mMsgTextView = view.findViewById(R.id.msgTextView);

        loadButton.setOnClickListener(v -> {
            mMsgTextView.setText(getString(R.string.begin_request_msg, urlEditText.getText().toString()));
            mUrl = urlEditText.getText().toString();
            String envoyUrl = envoyUrlEditText.getText().toString();

            CronetEngine.Builder engineBuilder = new CronetEngine.Builder(view.getContext());
            engineBuilder.setEnvoyUrl(envoyUrl);

            CronetEngine engine = engineBuilder.build();

            mResultWebView.getSettings().setJavaScriptEnabled(true);
            mResultWebView.setWebViewClient(new CronetWebViewClient(engine) {
                @Override
                public void onPageFinished(WebView view, String url) {
                    super.onPageFinished(view, url);
                    final String msg = "Completed";
                    WebViewFragment.this.getActivity().runOnUiThread(() -> mMsgTextView.setText(msg));
                }
            });
            mResultWebView.loadUrl(urlEditText.getText().toString());
        });
        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
    }
}
