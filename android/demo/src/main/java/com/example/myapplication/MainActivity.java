package com.example.myapplication;

import static org.greatfire.envoy.EnvoyTransportType.DIRECT;
import static org.greatfire.envoy.EnvoyTransportType.OKHTTP_ENVOY;
import static org.greatfire.envoy.EnvoyTransportType.CRONET_ENVOY;
import static org.greatfire.envoy.EnvoyTransportType.OKHTTP_MASQUE;
import static org.greatfire.envoy.EnvoyTransportType.CRONET_MASQUE;
import static org.greatfire.envoy.EnvoyTransportType.OKHTTP_PROXY;
import static org.greatfire.envoy.EnvoyTransportType.CRONET_PROXY;
import static org.greatfire.envoy.EnvoyTransportType.HTTP_ECH;
import static org.greatfire.envoy.EnvoyTransportType.HYSTERIA2;
import static org.greatfire.envoy.EnvoyTransportType.V2WS;
import static org.greatfire.envoy.EnvoyTransportType.V2SRTP;
import static org.greatfire.envoy.EnvoyTransportType.V2WECHAT;
import static org.greatfire.envoy.EnvoyTransportType.SHADOWSOCKS;
import static org.greatfire.envoy.EnvoyTransportType.OHTTP;

import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import androidx.fragment.app.FragmentActivity;

import org.greatfire.envoy.EnvoyNetworking;
import org.greatfire.envoy.EnvoyTestCallback;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;

public class MainActivity extends FragmentActivity {

    private static final String WIKI_URL = "https://www.wikipedia.org/";
    private static final String TAG = "EnvoyDemoApp";

    class DemoCallback implements EnvoyTestCallback {
        @Override
        public void reportTestSuccess(String testedUrl, String testedService, long time) {
            Log.d(TAG, "URL: " + testedUrl + " SUCCESS! Took: " + time + " ms");
            MainActivity.this.runOnUiThread(new Runnable() {
                public void run() {
                    displayOutput("VALID: " + testedService);
                    displayResults(testedUrl, testedService, true);
                }
            });
        }

        @Override
        public void reportTestFailure(String testedUrl, String testedService, long time) {
            Log.d(TAG, "URL: " + testedUrl + " failed. Took: " + time + " ms");
            MainActivity.this.runOnUiThread(new Runnable() {
                public void run() {
                    displayOutput("INVALID: " + testedService);
                    displayResults(testedUrl, testedService, false);
                }
            });
        }

        @Override
        public void reportTestBlocked(String testedUrl, String testedService) {
            Log.e(TAG, "URL: " + testedUrl + " is blocked, this shouldn't happen");
        }

        @Override
        public void reportOverallStatus(String status, long time) {
            Log.d(TAG, "All done, took: " + time + " ms");
            Log.d(TAG, "Final status: " + status);
        }
    }

    Secrets mSecrets;
    // NetworkIntentService mService;
    boolean mBound = false;
    TextView mOutputTextView;
    int mUrlCount = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        findViewById(R.id.runButton).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Log.d(TAG, "button pushed, submit urls");
                resetResults();
                start();
                findViewById(R.id.runButton).setEnabled(false);
            }
        });
        findViewById(R.id.runButton).setEnabled(false);

        mOutputTextView = findViewById(R.id.output);
        mSecrets = new Secrets();
        if (mSecrets.getdefProxy(getPackageName()) == null) {
            Log.w(TAG, "on create, no urls");
            mOutputTextView.setText("nothing found to test...\n*\n*\n*\n*\n*\n*\n*\n*\n*\n*\n*");
            return;
        } else {
            Log.d(TAG, "on create, submit urls");
            mOutputTextView.setText("*\n*\n*\n*\n*\n*\n*\n*\n*\n*\n*\n*");
            // submit();
            start();
        }
    }

    private void start() {

        String proxyList = mSecrets.getdefProxy(getPackageName());
        String[] proxyParts = proxyList.split(",");
        ArrayList<String> testUrls = new ArrayList<String>(Arrays.asList(proxyParts));
        ArrayList<String> directUrls = new ArrayList<String>(Arrays.asList(WIKI_URL));
        mUrlCount = 0;

        EnvoyNetworking envoy = new EnvoyNetworking();

        // XXX set the context here
        envoy.setContext(getApplicationContext());

        // set debug mode to ensure all urls are tested
        envoy.setTestAllUrls(true);

        for (int i = 0; i < testUrls.size(); i++) {
            mUrlCount = mUrlCount + 1;

            // used to exclude wiki url when testing wiki url set
            // not required for the current version of the test?
            if (!testUrls.get(i).equals(WIKI_URL)) {
                envoy.addEnvoyUrl(testUrls.get(i));
            }
        }

        envoy.setCallback(new DemoCallback())
            .connect();
    }

    void resetResults() {

        findViewById(R.id.directResult).setVisibility(View.GONE);
        findViewById(R.id.httpResult).setVisibility(View.GONE);
        findViewById(R.id.cronetResult).setVisibility(View.GONE);
        findViewById(R.id.echResult).setVisibility(View.GONE);
        findViewById(R.id.masqueResult).setVisibility(View.GONE);
        findViewById(R.id.ssResult).setVisibility(View.GONE);
        findViewById(R.id.hysteriaResult).setVisibility(View.GONE);
        findViewById(R.id.v2sResult).setVisibility(View.GONE);
        findViewById(R.id.v2wResult).setVisibility(View.GONE);
        findViewById(R.id.snowflakeResult).setVisibility(View.GONE);
        findViewById(R.id.meekResult).setVisibility(View.GONE);
        findViewById(R.id.ohttpResult).setVisibility(View.GONE);
    }

    void displayResults(String url, String service, boolean success) {

        if (service.equals(DIRECT.name())) {
            findViewById(R.id.directResult).setVisibility(View.VISIBLE);
            if (success) {
                findViewById(R.id.directSuccess).setVisibility(View.VISIBLE);
                findViewById(R.id.directFailure).setVisibility(View.GONE);
            } else {
                findViewById(R.id.directSuccess).setVisibility(View.GONE);
                findViewById(R.id.directFailure).setVisibility(View.VISIBLE);
            }
        } else if (service.equals(OKHTTP_ENVOY.name())) {
            findViewById(R.id.httpResult).setVisibility(View.VISIBLE);
            if (success) {
                findViewById(R.id.httpSuccess).setVisibility(View.VISIBLE);
                findViewById(R.id.httpFailure).setVisibility(View.GONE);
            } else {
                findViewById(R.id.httpSuccess).setVisibility(View.GONE);
                findViewById(R.id.httpFailure).setVisibility(View.VISIBLE);
            }
        } else if (service.equals(CRONET_ENVOY.name())) {
            findViewById(R.id.cronetResult).setVisibility(View.VISIBLE);
            if (success) {
                findViewById(R.id.cronetSuccess).setVisibility(View.VISIBLE);
                findViewById(R.id.cronetFailure).setVisibility(View.GONE);
            } else {
                findViewById(R.id.cronetSuccess).setVisibility(View.GONE);
                findViewById(R.id.cronetFailure).setVisibility(View.VISIBLE);
            }
        } else if (service.equals(HTTP_ECH.name())) {
            findViewById(R.id.echResult).setVisibility(View.VISIBLE);
            if (success) {
                findViewById(R.id.echSuccess).setVisibility(View.VISIBLE);
                findViewById(R.id.echFailure).setVisibility(View.GONE);
            } else {
                findViewById(R.id.echSuccess).setVisibility(View.GONE);
                findViewById(R.id.echFailure).setVisibility(View.VISIBLE);
            }
        } else if (service.equals(OKHTTP_MASQUE.name())) {
            findViewById(R.id.masqueResult).setVisibility(View.VISIBLE);
            if (success) {
                findViewById(R.id.masqueSuccess).setVisibility(View.VISIBLE);
                findViewById(R.id.masqueFailure).setVisibility(View.GONE);
            } else {
                findViewById(R.id.masqueSuccess).setVisibility(View.GONE);
                findViewById(R.id.masqueFailure).setVisibility(View.VISIBLE);
            }
        } else if (service.equals(SHADOWSOCKS.name())) {
            findViewById(R.id.ssResult).setVisibility(View.VISIBLE);
            if (success) {
                findViewById(R.id.ssSuccess).setVisibility(View.VISIBLE);
                findViewById(R.id.ssFailure).setVisibility(View.GONE);
            } else {
                findViewById(R.id.ssSuccess).setVisibility(View.GONE);
                findViewById(R.id.ssFailure).setVisibility(View.VISIBLE);
            }
        } else if (service.equals(HYSTERIA2.name())) {
            findViewById(R.id.hysteriaResult).setVisibility(View.VISIBLE);
            if (success) {
                findViewById(R.id.hysteriaSuccess).setVisibility(View.VISIBLE);
                findViewById(R.id.hysteriaFailure).setVisibility(View.GONE);
            } else {
                findViewById(R.id.hysteriaSuccess).setVisibility(View.GONE);
                findViewById(R.id.hysteriaFailure).setVisibility(View.VISIBLE);
            }
        } else if (service.equals(V2SRTP.name())) {
            findViewById(R.id.v2sResult).setVisibility(View.VISIBLE);
            if (success) {
                findViewById(R.id.v2sSuccess).setVisibility(View.VISIBLE);
                findViewById(R.id.v2sFailure).setVisibility(View.GONE);
            } else {
                findViewById(R.id.v2sSuccess).setVisibility(View.GONE);
                findViewById(R.id.v2sFailure).setVisibility(View.VISIBLE);
            }
        } else if (service.equals(V2WECHAT.name())) {
            findViewById(R.id.v2wResult).setVisibility(View.VISIBLE);
            if (success) {
                findViewById(R.id.v2wSuccess).setVisibility(View.VISIBLE);
                findViewById(R.id.v2wFailure).setVisibility(View.GONE);
            } else {
                findViewById(R.id.v2wSuccess).setVisibility(View.GONE);
                findViewById(R.id.v2wFailure).setVisibility(View.VISIBLE);
            }
        }else if (service.equals(OHTTP.name())) {
            findViewById(R.id.ohttpResult).setVisibility(View.VISIBLE);
            if (success) {
                findViewById(R.id.ohttpSuccess).setVisibility(View.VISIBLE);
                findViewById(R.id.ohttpFailure).setVisibility(View.GONE);
            } else {
                findViewById(R.id.ohttpSuccess).setVisibility(View.GONE);
                findViewById(R.id.ohttpFailure).setVisibility(View.VISIBLE);
            }
        // } else if (service.equals(ENVOY_SERVICE_SNOWFLAKE)) {
        //     findViewById(R.id.snowflakeResult).setVisibility(View.VISIBLE);
        //     if (success) {
        //         findViewById(R.id.snowflakeSuccess).setVisibility(View.VISIBLE);
        //         findViewById(R.id.snowflakeFailure).setVisibility(View.GONE);
        //     } else {
        //         findViewById(R.id.snowflakeSuccess).setVisibility(View.GONE);
        //         findViewById(R.id.snowflakeFailure).setVisibility(View.VISIBLE);
        //     }
        // } else if (service.equals(ENVOY_SERVICE_MEEK)) {
        //     findViewById(R.id.meekResult).setVisibility(View.VISIBLE);
        //     if (success) {
        //         findViewById(R.id.meekSuccess).setVisibility(View.VISIBLE);
        //         findViewById(R.id.meekFailure).setVisibility(View.GONE);
        //     } else {
        //         findViewById(R.id.meekSuccess).setVisibility(View.GONE);
        //         findViewById(R.id.meekFailure).setVisibility(View.VISIBLE);
        //     }
        } else {
            // unsupported service
            Log.w(TAG, "unsupported service (display): " + service);
        }

        // log results to file
        logOutput(url, service, success);

        // if results were received, enable rerun test button
        findViewById(R.id.runButton).setEnabled(true);
    }

    void displayOutput(String output) {

        String lines = mOutputTextView.getText().toString();
        String[] lineList = lines.split("\n");
        String newLines = output;
        for (int i = 0; i < lineList.length - 1; i++) {
            newLines = newLines + "\n" + lineList[i];
        }
        mOutputTextView.setText(newLines);
    }

    void logOutput(String url, String service, boolean success) {
        if (success) {
            logOutput("SUCCESS," + service + "," + url);
        } else {
            logOutput("FAILURE," + service + "," + url);
        }
    }

    void logOutput(String output) {

        long logMs = System.currentTimeMillis();
        SimpleDateFormat isoFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ");
        String logDate = isoFormat.format(new Date(logMs));

        try {
            File logPath = getApplicationContext().getExternalFilesDir(null);
            File logFile = new File(logPath, "demo_log");
            FileOutputStream logStream = new FileOutputStream(logFile, true);
            String logString = logDate + "," + output + "\n";
            try {
                logStream.write(logString.getBytes());
            } finally {
                logStream.close();
            }

            mUrlCount = mUrlCount - 1;
            if (mUrlCount == 0) {
                displayOutput("LOG FILE PATH: " + logFile.getAbsolutePath());
            } else {
                displayOutput("URLS REMAINING: " + mUrlCount);
            }
        } catch (IOException e) {
            Log.e(TAG, "exception when logging", e);
        }
    }
}
