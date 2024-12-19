package com.example.myapplication;

import static org.greatfire.envoy.ConstantsKt.ENVOY_BROADCAST_VALIDATION_ENDED;
import static org.greatfire.envoy.ConstantsKt.ENVOY_BROADCAST_VALIDATION_FAILED;
import static org.greatfire.envoy.ConstantsKt.ENVOY_BROADCAST_VALIDATION_SUCCEEDED;
import static org.greatfire.envoy.ConstantsKt.ENVOY_DATA_SERVICE_FAILED;
import static org.greatfire.envoy.ConstantsKt.ENVOY_DATA_SERVICE_SUCCEEDED;
import static org.greatfire.envoy.ConstantsKt.ENVOY_DATA_URL_FAILED;
import static org.greatfire.envoy.ConstantsKt.ENVOY_DATA_URL_SUCCEEDED;
import static org.greatfire.envoy.ConstantsKt.ENVOY_DATA_VALIDATION_ENDED_CAUSE;
import static org.greatfire.envoy.ConstantsKt.ENVOY_DATA_VALIDATION_MS;
import static org.greatfire.envoy.ConstantsKt.ENVOY_SERVICE_DIRECT;
import static org.greatfire.envoy.ConstantsKt.ENVOY_SERVICE_ENVOY;
import static org.greatfire.envoy.ConstantsKt.ENVOY_SERVICE_HTTPS;
import static org.greatfire.envoy.ConstantsKt.ENVOY_SERVICE_HYSTERIA;
import static org.greatfire.envoy.ConstantsKt.ENVOY_SERVICE_MEEK;
import static org.greatfire.envoy.ConstantsKt.ENVOY_SERVICE_SNOWFLAKE;
import static org.greatfire.envoy.ConstantsKt.ENVOY_SERVICE_SS;
import static org.greatfire.envoy.ConstantsKt.ENVOY_SERVICE_V2SRTP;
import static org.greatfire.envoy.ConstantsKt.ENVOY_SERVICE_V2WECHAT;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import androidx.fragment.app.FragmentActivity;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.chromium.net.CronetEngine;
import org.greatfire.envoy.CronetInterceptor;
import org.greatfire.envoy.CronetNetworking;
import org.greatfire.envoy.NetworkIntentService;
import org.greatfire.envoy.UrlUtil;

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
    private static final String TAG = "FOO_1"; // "EnvoyDemoApp";

    Secrets mSecrets;
    NetworkIntentService mService;
    boolean mBound = false;
    TextView mOutputTextView;
    int mUrlCount = 0;

    String httpsUrl = "";
    String envoyUrl = "";
    String ssUrl = "";
    String hystUrl = "";
    String v2sUrl = "";
    String v2wUrl = "";
    String snowUrl = "";
    String meekUrl = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // register to receive test results
        IntentFilter filter = new IntentFilter();
        filter.addAction(ENVOY_BROADCAST_VALIDATION_SUCCEEDED);
        filter.addAction(ENVOY_BROADCAST_VALIDATION_FAILED);
        filter.addAction(ENVOY_BROADCAST_VALIDATION_ENDED);
        LocalBroadcastManager.getInstance(this).registerReceiver(mBroadcastReceiver, filter);

        findViewById(R.id.runButton).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Log.d(TAG, "button pushed, submit urls");
                resetResults();
                submit();
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
            submit();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mBroadcastReceiver);
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Bind to NetworkIntentService
        Intent intent = new Intent(this, NetworkIntentService.class);
        bindService(intent, connection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onStop() {
        super.onStop();
        unbindService(connection);
        mBound = false;
    }

    /**
     * Defines callbacks for service binding, passed to bindService()
     */
    private final ServiceConnection connection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName className,
                                       IBinder service) {
            // We've bound to LocalService, cast the IBinder and get LocalService instance
            NetworkIntentService.NetworkBinder binder = (NetworkIntentService.NetworkBinder) service;
            mService = binder.getService();
            mBound = true;
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            mBound = false;
        }
    };

    protected final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {

        private static final String TAG = "FOO_2"; // "EnvoyDemoReceiver";

        @Override
        public void onReceive(Context context, Intent intent) {

            Log.d(TAG, "broadcast receiver triggered");

            if (intent != null && context != null) {

                Log.d(TAG, "action received: " + intent.getAction());

                if (intent.getAction().equals(ENVOY_BROADCAST_VALIDATION_SUCCEEDED)) {

                    // if an unknown cronet engine is running, shut it down
                    if (CronetNetworking.cronetEngine() != null) {
                        Log.d(TAG, "cronet already running (ignore)");
                    }

                    String validUrl = intent.getStringExtra(ENVOY_DATA_URL_SUCCEEDED);
                    if (validUrl == null || validUrl.isEmpty()) {
                        Log.w(TAG, "received a valid url that was empty or null");
                    } else {
                        Log.d(TAG, "received a valid url: " + validUrl);
                    }

                    String validService = intent.getStringExtra(ENVOY_DATA_SERVICE_SUCCEEDED);
                    if (validService == null || validService.isEmpty()) {
                        Log.w(TAG, "received a valid service that was empty or null");
                    } else {
                        Log.d(TAG, "received a valid service: " + validService);
                    }

                    String sanitizedUrl = "***";
                    if (validUrl != null && validService != null) {
                        sanitizedUrl = UrlUtil.sanitizeUrl(validUrl, validService);
                        if (validUrl.startsWith(ENVOY_SERVICE_ENVOY) && validService.startsWith(ENVOY_SERVICE_HTTPS)) {
                            validService = ENVOY_SERVICE_ENVOY;
                        }
                    }

                    Long validationMs = intent.getLongExtra(ENVOY_DATA_VALIDATION_MS, 0L);
                    Long validationSeconds = 0L;
                    if (validationMs <= 0L) {
                        Log.w(TAG, "received a successful validation with an invalid duration");
                    } else {
                        validationSeconds = validationMs / 1000L;
                        if (validationSeconds < 1L) {
                            validationSeconds = 1L;
                        }
                        Log.d(TAG, "received a successful validation with a duration: " + validationSeconds);
                    }

                    //showDialog("SUCCESS", validService + " succeeded", true);

                    final String finalService = validService;
                    final String finalUrl = sanitizedUrl;
                    final Long finalSeconds = validationSeconds;

                    if (validUrl == null || validUrl.isEmpty()) {
                        Log.w(TAG, "success status included no valid cronet url, can't continue");
                        displayOutput("validation returned no url");
                        displayOutput("INVALID: " + validService);
                        displayResults(finalService, false);
                        return;
                    }

                    if (validUrl.equals(WIKI_URL)) {
                        Log.d(TAG, "success status for the direct connection url, don't continue");
                        displayOutput("validation returned direct url");
                        displayOutput("VALID: " + validService + " - " + sanitizedUrl + " (" + validationSeconds + " seconds)");
                        displayResults(finalService, true);
                        return;
                    }

                    // envoy success, try to connect
                    Log.d(TAG, "set up cronet engine with envoy url " + validUrl);
                    CronetNetworking.initializeCronetEngine(context, validUrl); // reInitializeIfNeeded set to false
                    CronetEngine engine = CronetNetworking.cronetEngine();
                    Log.d(TAG, "cronet engine created, version " + engine.getVersionString());

                    new Thread() {
                        @Override
                        public void run() {

                            OkHttpClient client = new OkHttpClient.Builder()
                                    .addInterceptor(new CronetInterceptor(engine))
                                    .build();
                            okhttp3.Request request = new okhttp3.Request.Builder().url(WIKI_URL).build();
                            try (okhttp3.Response response = client.newCall(request).execute()) {

                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        Log.d(TAG, "update ui with response");
                                        displayOutput("successful connection with " + finalService + " - " + finalUrl);
                                        displayOutput("VALID: " + finalService + " - " + finalUrl + " (" + finalSeconds + " seconds)");
                                        displayResults(finalService, true);
                                    }
                                });

                                Log.d(TAG, "proxied request succeeded");

                            } catch (IOException e) {
                                Log.e(TAG, "proxied request caused okhttp error: ", e);
                                displayOutput("failed connection with " + finalService + " - " + finalUrl);
                                displayOutput("INVALID: " + finalService + " - " + finalUrl);
                                displayResults(finalService, false);
                            }
                        }
                    }.start();

                } else if (intent.getAction() == ENVOY_BROADCAST_VALIDATION_FAILED) {

                    String invalidUrl = intent.getStringExtra(ENVOY_DATA_URL_FAILED);
                    if (invalidUrl == null || invalidUrl.isEmpty()) {
                        Log.e(TAG, "received an invalid url that was empty or null");
                    }

                    String invalidService = intent.getStringExtra(ENVOY_DATA_SERVICE_FAILED);
                    if (invalidService == null || invalidService.isEmpty()) {
                        Log.e(TAG, "received an invalid service that was empty or null");
                    }

                    String sanitizedUrl = "";
                    if (invalidUrl != null && invalidService != null) {
                        sanitizedUrl = UrlUtil.sanitizeUrl(invalidUrl, invalidService);
                        if (invalidUrl.startsWith(ENVOY_SERVICE_ENVOY) && invalidService.startsWith(ENVOY_SERVICE_HTTPS)) {
                            invalidService = ENVOY_SERVICE_ENVOY;
                        }
                    }

                    displayOutput("INVALID: " + invalidService + " - " + sanitizedUrl);
                    displayResults(invalidService, false);

                    Log.e(TAG, "validation failed");

                } else if (intent.getAction() == ENVOY_BROADCAST_VALIDATION_ENDED) {

                    Long validationMs = intent.getLongExtra(ENVOY_DATA_VALIDATION_MS, 0L);
                    Long validationSeconds = 0L;
                    if (validationMs <= 0L) {
                        Log.e(TAG, "received a validation ended with an invalid duration");
                    } else {
                        validationSeconds = validationMs / 1000L;
                        if (validationSeconds < 1L) {
                            validationSeconds = 1L;
                        }
                    }

                    String validationCause = intent.getStringExtra(ENVOY_DATA_VALIDATION_ENDED_CAUSE);
                    if (validationCause == null || validationCause.isEmpty()) {
                        Log.e(TAG, "received a validation cause that was empty or null");
                    }

                    displayOutput("COMPLETE: " + validationSeconds + " seconds");
                    Log.e(TAG, "validation ended");

                } else {
                    Log.e(TAG, "received an unexpected intent: " + intent.getAction());
                }
            } else {
                Log.e(TAG, "receiver triggered but context or intent was null");
            }
        }
    };

    private void submit() {

        // reset log file
        try {
            File logPath = getApplicationContext().getExternalFilesDir(null);
            File logFile = new File(logPath, "demo_log");
            FileOutputStream logStream = new FileOutputStream(logFile, false);
            try {
                logStream.write("".getBytes());
            } finally {
                logStream.close();
            }
        } catch (IOException e) {
            Log.e(TAG, "exception when clearing log", e);
        }

        String proxyList = mSecrets.getdefProxy(getPackageName());
        String[] proxyParts = proxyList.split(",");
        ArrayList<String> testUrls = new ArrayList<String>(Arrays.asList(proxyParts));
        ArrayList<String> directUrls = new ArrayList<String>(Arrays.asList(WIKI_URL));
        mUrlCount = 0;

        for (int i = 0; i < testUrls.size(); i++) {
            mUrlCount = mUrlCount + 1;
            if (testUrls.get(i).equals(WIKI_URL)) {
                // NO-OP
            } else if (testUrls.get(i).startsWith("https")) {
                httpsUrl = testUrls.get(i);
            } else if (testUrls.get(i).startsWith("envoy")) {
                envoyUrl = testUrls.get(i);
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
        }

        if (mSecrets.gethystCert(getPackageName()) != null) {
            submit(testUrls, mSecrets.gethystCert(getPackageName()), directUrls);
        } else {
            submit(testUrls, null, directUrls);
        }
    }

    private void submit(ArrayList<String> testUrls, String testCert, ArrayList<String> directUrls) {

        Log.d(TAG, "submit " + testUrls.size() + " urls");

        // clear result window
        mOutputTextView.setText("waiting for results...\n*\n*\n*\n*\n*\n*\n*\n*\n*\n*\n*");

        ArrayList<String> emptyList = new ArrayList<String>();

        NetworkIntentService.submit(
                this,
                testUrls,
                directUrls,
                testCert,
                emptyList,
                1,
                1,
                1
        );
    }

    void resetResults() {
        findViewById(R.id.directResult).setVisibility(View.GONE);
        findViewById(R.id.httpsResult).setVisibility(View.GONE);
        findViewById(R.id.envoyResult).setVisibility(View.GONE);
        findViewById(R.id.ssResult).setVisibility(View.GONE);
        findViewById(R.id.hysteriaResult).setVisibility(View.GONE);
        findViewById(R.id.v2sResult).setVisibility(View.GONE);
        findViewById(R.id.v2wResult).setVisibility(View.GONE);
        findViewById(R.id.snowflakeResult).setVisibility(View.GONE);
        findViewById(R.id.meekResult).setVisibility(View.GONE);
    }

    void displayResults(String service, boolean success) {
        if (service.equals(ENVOY_SERVICE_DIRECT)) {
            findViewById(R.id.directResult).setVisibility(View.VISIBLE);
            if (success) {
                findViewById(R.id.directSuccess).setVisibility(View.VISIBLE);
                findViewById(R.id.directFailure).setVisibility(View.GONE);
            } else {
                findViewById(R.id.directSuccess).setVisibility(View.GONE);
                findViewById(R.id.directFailure).setVisibility(View.VISIBLE);
            }
        } else if (service.equals(ENVOY_SERVICE_HTTPS)) {
            findViewById(R.id.httpsResult).setVisibility(View.VISIBLE);
            if (success) {
                findViewById(R.id.httpsSuccess).setVisibility(View.VISIBLE);
                findViewById(R.id.httpsFailure).setVisibility(View.GONE);
            } else {
                findViewById(R.id.httpsSuccess).setVisibility(View.GONE);
                findViewById(R.id.httpsFailure).setVisibility(View.VISIBLE);
            }
        } else if (service.equals(ENVOY_SERVICE_ENVOY)) {
            findViewById(R.id.envoyResult).setVisibility(View.VISIBLE);
            if (success) {
                findViewById(R.id.envoySuccess).setVisibility(View.VISIBLE);
                findViewById(R.id.envoyFailure).setVisibility(View.GONE);
            } else {
                findViewById(R.id.envoySuccess).setVisibility(View.GONE);
                findViewById(R.id.envoyFailure).setVisibility(View.VISIBLE);
            }
        } else if (service.equals(ENVOY_SERVICE_SS)) {
            findViewById(R.id.ssResult).setVisibility(View.VISIBLE);
            if (success) {
                findViewById(R.id.ssSuccess).setVisibility(View.VISIBLE);
                findViewById(R.id.ssFailure).setVisibility(View.GONE);
            } else {
                findViewById(R.id.ssSuccess).setVisibility(View.GONE);
                findViewById(R.id.ssFailure).setVisibility(View.VISIBLE);
            }
        } else if (service.equals(ENVOY_SERVICE_HYSTERIA)) {
            findViewById(R.id.hysteriaResult).setVisibility(View.VISIBLE);
            if (success) {
                findViewById(R.id.hysteriaSuccess).setVisibility(View.VISIBLE);
                findViewById(R.id.hysteriaFailure).setVisibility(View.GONE);
            } else {
                findViewById(R.id.hysteriaSuccess).setVisibility(View.GONE);
                findViewById(R.id.hysteriaFailure).setVisibility(View.VISIBLE);
            }
        } else if (service.equals(ENVOY_SERVICE_V2SRTP)) {
            findViewById(R.id.v2sResult).setVisibility(View.VISIBLE);
            if (success) {
                findViewById(R.id.v2sSuccess).setVisibility(View.VISIBLE);
                findViewById(R.id.v2sFailure).setVisibility(View.GONE);
            } else {
                findViewById(R.id.v2sSuccess).setVisibility(View.GONE);
                findViewById(R.id.v2sFailure).setVisibility(View.VISIBLE);
            }
        } else if (service.equals(ENVOY_SERVICE_V2WECHAT)) {
            findViewById(R.id.v2wResult).setVisibility(View.VISIBLE);
            if (success) {
                findViewById(R.id.v2wSuccess).setVisibility(View.VISIBLE);
                findViewById(R.id.v2wFailure).setVisibility(View.GONE);
            } else {
                findViewById(R.id.v2wSuccess).setVisibility(View.GONE);
                findViewById(R.id.v2wFailure).setVisibility(View.VISIBLE);
            }
        } else if (service.equals(ENVOY_SERVICE_SNOWFLAKE)) {
            findViewById(R.id.snowflakeResult).setVisibility(View.VISIBLE);
            if (success) {
                findViewById(R.id.snowflakeSuccess).setVisibility(View.VISIBLE);
                findViewById(R.id.snowflakeFailure).setVisibility(View.GONE);
            } else {
                findViewById(R.id.snowflakeSuccess).setVisibility(View.GONE);
                findViewById(R.id.snowflakeFailure).setVisibility(View.VISIBLE);
            }
        } else if (service.equals(ENVOY_SERVICE_MEEK)) {
            findViewById(R.id.meekResult).setVisibility(View.VISIBLE);
            if (success) {
                findViewById(R.id.meekSuccess).setVisibility(View.VISIBLE);
                findViewById(R.id.meekFailure).setVisibility(View.GONE);
            } else {
                findViewById(R.id.meekSuccess).setVisibility(View.GONE);
                findViewById(R.id.meekFailure).setVisibility(View.VISIBLE);
            }
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
        String lines = mOutputTextView.getText().toString();
        String[] lineList = lines.split("\n");
        String newLines = output;
        for (int i = 0; i < lineList.length - 1; i++) {
            newLines = newLines + "\n" + lineList[i];
        }
        mOutputTextView.setText(newLines);
    }

    void logOutput(String service, boolean success) {
        String originalUrl = "???";
        if (service.equals(ENVOY_SERVICE_DIRECT)) {
            originalUrl = WIKI_URL;
        } else if (service.equals(ENVOY_SERVICE_HTTPS)) {
            originalUrl = httpsUrl;
        } else if (service.equals(ENVOY_SERVICE_ENVOY)) {
            originalUrl = envoyUrl;
        } else if (service.equals(ENVOY_SERVICE_SS)) {
            originalUrl = ssUrl;
        } else if (service.equals(ENVOY_SERVICE_HYSTERIA)) {
            originalUrl = hystUrl;
        } else if (service.equals(ENVOY_SERVICE_V2SRTP)) {
            originalUrl = v2sUrl;
        } else if (service.equals(ENVOY_SERVICE_V2WECHAT)) {
            originalUrl = v2wUrl;
        } else if (service.equals(ENVOY_SERVICE_SNOWFLAKE)) {
            originalUrl = snowUrl;
        } else if (service.equals(ENVOY_SERVICE_MEEK)) {
            originalUrl = meekUrl;
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
