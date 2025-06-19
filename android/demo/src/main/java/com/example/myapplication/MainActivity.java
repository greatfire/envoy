package com.example.myapplication;

import static org.greatfire.envoy.EnvoyServiceType.DIRECT;
import static org.greatfire.envoy.EnvoyServiceType.OKHTTP_ENVOY;
import static org.greatfire.envoy.EnvoyServiceType.CRONET_ENVOY;
import static org.greatfire.envoy.EnvoyServiceType.OKHTTP_MASQUE;
import static org.greatfire.envoy.EnvoyServiceType.CRONET_MASQUE;
import static org.greatfire.envoy.EnvoyServiceType.OKHTTP_PROXY;
import static org.greatfire.envoy.EnvoyServiceType.CRONET_PROXY;
import static org.greatfire.envoy.EnvoyServiceType.HTTP_ECH;
import static org.greatfire.envoy.EnvoyServiceType.HYSTERIA2;
import static org.greatfire.envoy.EnvoyServiceType.V2WS;
import static org.greatfire.envoy.EnvoyServiceType.V2SRTP;
import static org.greatfire.envoy.EnvoyServiceType.V2WECHAT;
import static org.greatfire.envoy.EnvoyServiceType.SHADOWSOCKS;

import static org.greatfire.envoy.EnvoyTestStatus.PASSED;
import static org.greatfire.envoy.EnvoyTestStatus.FAILED;
import static org.greatfire.envoy.EnvoyTestStatus.EMPTY;
import static org.greatfire.envoy.EnvoyTestStatus.BLOCKED;
import static org.greatfire.envoy.EnvoyTestStatus.TIMEOUT;
import static org.greatfire.envoy.EnvoyTestStatus.UNKNOWN;


import android.content.Context;
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
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Objects;

import okhttp3.OkHttpClient;

public class MainActivity extends FragmentActivity {

    private static final String WIKI_URL = "https://www.wikipedia.org/";
    private static final String TAG = "FOO"; // "EnvoyDemoApp";

    class DemoCallback implements EnvoyTestCallback {
        @Override
        public void reportTestSuccess(String testedUrl, String testedService, long time) {
            Log.d(TAG, "URL: " + testedUrl + " SUCCESS! Took: " + time + " ms");
            MainActivity.this.runOnUiThread(new Runnable() {
                public void run() {
                    displayOutput("VALID: " + testedService);
                    displayResults(testedService, true);
                }
            });
        }

        @Override
        public void reportTestFailure(String testedUrl, String testedService, long time) {
            Log.d(TAG, "URL: " + testedUrl + " failed. Took: " + time + " ms");
            MainActivity.this.runOnUiThread(new Runnable() {
                public void run() {
                    displayOutput("INVALID: " + testedService);
                    displayResults(testedService, false);
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

    String httpUrl = "";
    String cronetUrl = "";
    String echUrl = "";
    String masqueUrl = "";
    String ssUrl = "";
    String hystUrl = "";
    String v2sUrl = "";
    String v2wUrl = "";
    String snowUrl = "";
    String meekUrl = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        Log.d("FOO", "CREATE");

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

        Log.d("FOO", "START");

        String proxyList = mSecrets.getdefProxy(getPackageName());
        String[] proxyParts = proxyList.split(",");
        ArrayList<String> testUrls = new ArrayList<String>(Arrays.asList(proxyParts));
        ArrayList<String> directUrls = new ArrayList<String>(Arrays.asList(WIKI_URL));
        mUrlCount = 0;

        EnvoyNetworking envoy = new EnvoyNetworking();

        // XXX where to get the context?
        // envoy.setContext()
        envoy.setContext(getApplicationContext());

        for (int i = 0; i < testUrls.size(); i++) {
            mUrlCount = mUrlCount + 1;

            Log.d("FOO", "TEST URL: " + testUrls.get(i));

            // This feels a little silly
            if (testUrls.get(i).equals(WIKI_URL)) {
                // NO-OP
            } else if (testUrls.get(i).startsWith("https")) {
                // https urls spawn multiple tests
                httpUrl = testUrls.get(i);
                cronetUrl = testUrls.get(i);
                echUrl = testUrls.get(i);
            } else if (testUrls.get(i).startsWith("masque")) {
                masqueUrl = testUrls.get(i);
            } else if (testUrls.get(i).startsWith("ss")) {
                ssUrl = testUrls.get(i);
            } else if (testUrls.get(i).startsWith("hysteria")) {
                hystUrl = testUrls.get(i);
            } else if (testUrls.get(i).startsWith("v2srtp")) {
                v2sUrl = testUrls.get(i);
            } else if (testUrls.get(i).startsWith("v2wechat")) {
                v2wUrl = testUrls.get(i);
            } else if (testUrls.get(i).startsWith("snowflake")) {
                snowUrl = testUrls.get(i);
            } else if (testUrls.get(i).startsWith("meek")) {
                meekUrl = testUrls.get(i);
            }

            if (!testUrls.get(i).equals(WIKI_URL)) {
                envoy.addEnvoyUrl(testUrls.get(i));
            }
        }

        envoy.setCallback(new DemoCallback())
            .connect();
    }

    void resetResults() {

        Log.d("FOO", "RESET");

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
    }

    void displayResults(String service, boolean success) {

        Log.d("FOO", "DISPLAY: " + service + " / " + success);

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
        logOutput(service, success);

        // if results were received, enable rerun test button
        findViewById(R.id.runButton).setEnabled(true);
    }

    void displayOutput(String output) {

        Log.d("FOO", "DISPLAY");

        String lines = mOutputTextView.getText().toString();
        String[] lineList = lines.split("\n");
        String newLines = output;
        for (int i = 0; i < lineList.length - 1; i++) {
            newLines = newLines + "\n" + lineList[i];
        }
        mOutputTextView.setText(newLines);
    }

    void logOutput(String service, boolean success) {

        Log.d("FOO", "LOG(1)");

        String originalUrl = "???";
        if (service.equals(DIRECT.name())) {
            originalUrl = WIKI_URL;
        } else if (service.equals(OKHTTP_ENVOY.name())) {
            originalUrl = httpUrl;
        } else if (service.equals(CRONET_ENVOY.name())) {
            originalUrl = cronetUrl;
        } else if (service.equals(HTTP_ECH.name())) {
            originalUrl = echUrl;
        } else if (service.equals(OKHTTP_MASQUE.name())) {
            originalUrl = masqueUrl;
        } else if (service.equals(SHADOWSOCKS.name())) {
            originalUrl = ssUrl;
        } else if (service.equals(HYSTERIA2.name())) {
            originalUrl = hystUrl;
        } else if (service.equals(V2SRTP.name())) {
            originalUrl = v2sUrl;
        } else if (service.equals(V2WECHAT.name())) {
            originalUrl = v2wUrl;
        // } else if (service.equals("SNOWFLAKE")) {
        //     originalUrl = snowUrl;
        // } else if (service.equals("MEEK")) {
        //     originalUrl = meekUrl;
        } else {
            // unsupported service
            Log.w(TAG, "unsupported service (log): " + service);
        }

        if (success) {
            logOutput("SUCCESS," + service + "," + originalUrl);
        } else {
            logOutput("FAILURE," + service + "," + originalUrl);
        }
    }

    void logOutput(String output) {

        Log.d("FOO", "START(2)");

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
