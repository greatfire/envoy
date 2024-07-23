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
import static org.greatfire.envoy.ConstantsKt.ENVOY_SERVICE_ENVOY;
import static org.greatfire.envoy.ConstantsKt.ENVOY_SERVICE_HTTPS;

import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.fragment.app.FragmentActivity;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.chromium.net.CronetEngine;
import org.greatfire.envoy.CronetInterceptor;
import org.greatfire.envoy.CronetNetworking;
import org.greatfire.envoy.NetworkIntentService;
import org.greatfire.envoy.UrlUtil;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Objects;

import okhttp3.OkHttpClient;

public class MainActivity extends FragmentActivity {

    private static final String WIKI_URL = "https://www.wikipedia.org/";
    private static final String TAG = "EnvoyDemoApp";

    Secrets mSecrets;
    NetworkIntentService mService;
    boolean mBound = false;
    TextView mMsgTextView;
    TextView mResultTextView;
    Dialog mResultDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mSecrets = new Secrets();

        // register to receive test results
        IntentFilter filter = new IntentFilter();
        filter.addAction(ENVOY_BROADCAST_VALIDATION_SUCCEEDED);
        filter.addAction(ENVOY_BROADCAST_VALIDATION_FAILED);
        filter.addAction(ENVOY_BROADCAST_VALIDATION_ENDED);
        LocalBroadcastManager.getInstance(this).registerReceiver(mBroadcastReceiver, filter);

        findViewById(R.id.directButton).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                submitDirectUrl(WIKI_URL);
            }
        });

        findViewById(R.id.envoyButton).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                submitTestUrl(mSecrets.getenvoyUrl(getPackageName()));
            }
        });

        findViewById(R.id.ssButton).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                submitTestUrl(mSecrets.getshadowsocksUrl(getPackageName()));
            }
        });

        findViewById(R.id.v2sButton).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                submitTestUrl(mSecrets.getv2srtpUrl(getPackageName()));
            }
        });

        findViewById(R.id.v2wButton).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                submitTestUrl(mSecrets.getv2wechatUrl(getPackageName()));
            }
        });

        findViewById(R.id.snowflakeButton).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                submitTestUrl(mSecrets.getsnowflakeUrl(getPackageName()));
            }
        });

        findViewById(R.id.meekButton).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                submitTestUrl(mSecrets.getmeekUrl(getPackageName()));
            }
        });

        mMsgTextView = findViewById(R.id.msgTextView);
        mMsgTextView.setText("*\n*\n*\n*");

        mResultTextView = findViewById(R.id.resultTextView);
        mResultTextView.setText("waiting for result...");
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

    private void showDialog(String title, String message, boolean result) {

        mResultDialog = new Dialog(this);
        mResultDialog.setContentView(R.layout.result_dialog);
        TextView titleText = mResultDialog.findViewById(R.id.dialog_title);
        titleText.setText(title);
        if (result) {
            titleText.setBackgroundColor(getResources().getColor(android.R.color.holo_green_light));
        } else {
            titleText.setBackgroundColor(getResources().getColor(android.R.color.holo_red_light));
        }
        TextView messageText = mResultDialog.findViewById(R.id.dialog_message);
        messageText.setText(message);
        Button okButton = mResultDialog.findViewById(R.id.dialog_button);
        okButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mResultDialog.dismiss();
            }
        });
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        mResultDialog.getWindow().setLayout(metrics.widthPixels - 100, ViewGroup.LayoutParams.WRAP_CONTENT);
        mResultDialog.show();
    }

    protected final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {

        private static final String TAG = "EnvoyDemoReceiver";

        @Override
        public void onReceive(Context context, Intent intent) {

            Log.e(TAG, "broadcast receiver triggered");

            if (intent != null && context != null) {

                Log.e(TAG, "action received: " + intent.getAction());

                if (intent.getAction().equals(ENVOY_BROADCAST_VALIDATION_SUCCEEDED)) {

                    // if an unknown cronet engine is running, shut it down
                    if (CronetNetworking.cronetEngine() != null) {
                        Log.e(TAG, "cronet already running (ignore)");
                        // CronetNetworking.cronetEngine().shutdown();
                    }

                    String validUrl = intent.getStringExtra(ENVOY_DATA_URL_SUCCEEDED);
                    if (validUrl == null || validUrl.isEmpty()) {
                        Log.e(TAG, "received a valid url that was empty or null");
                    } else {
                        Log.e(TAG, "received a valid url: " + validUrl);
                    }

                    String validService = intent.getStringExtra(ENVOY_DATA_SERVICE_SUCCEEDED);
                    if (validService == null || validService.isEmpty()) {
                        Log.e(TAG, "received a valid service that was empty or null");
                    } else {
                        Log.e(TAG, "received a valid service: " + validService);
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
                        Log.e(TAG, "received a successful validation with an invalid duration");
                    } else {
                        validationSeconds = validationMs / 1000L;
                        if (validationSeconds < 1L) {
                            validationSeconds = 1L;
                        }
                        Log.e(TAG, "received a successful validation with a duration: " + validationSeconds);
                    }

                    String lines = mMsgTextView.getText().toString();
                    String[] lineList = lines.split("\n");
                    String newLines = "SUCCESS: " + validService + " - " + sanitizedUrl + " (" + validationSeconds + " seconds)";
                    for (int i = 0; i < lineList.length - 1; i++) {
                        newLines = newLines + "\n" + lineList[i];
                    }
                    mMsgTextView.setText(newLines);

                    showDialog("SUCCESS", validService + " succeeded", true);

                    if (validUrl == null || validUrl.isEmpty()) {
                        Log.e(TAG, "success status included no valid cronet url, can't continue");
                        return;
                    }

                    if (validUrl.equals(WIKI_URL)) {
                        Log.e(TAG, "success status for the direct connection url, don't continue");
                        return;
                    }

                    // envoy success, try to connect
                    Log.e(TAG, "set up cronet engine with envoy url " + validUrl);
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
                                String responseString = Objects.requireNonNull(response.body()).string();
                                Log.d(TAG, "proxied request returns " + responseString);

                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        Log.d(TAG, "update ui with response");
                                        mResultTextView.setText(responseString);
                                    }
                                });

                            } catch (IOException e) {
                                Log.e(TAG, "proxied request caused okhttp error: ", e);
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

                    String lines = mMsgTextView.getText().toString();
                    String[] lineList = lines.split("\n");
                    String newLines = "FAILURE: " + invalidService + " - " + sanitizedUrl;
                    for (int i = 0; i < lineList.length - 1; i++) {
                        newLines = newLines + "\n" + lineList[i];
                    }
                    mMsgTextView.setText(newLines);

                    showDialog("FAILURE", invalidService + " failed", false);

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

                    String lines = mMsgTextView.getText().toString();
                    String[] lineList = lines.split("\n");
                    if (lineList[0].startsWith("FAILURE")) {
                        String newLines = lineList[0] + " (" + validationSeconds + " seconds, " + validationCause + ")";
                        for (int i = 1; i < lineList.length; i++) {
                            newLines = newLines + "\n" + lineList[i];
                        }
                        mMsgTextView.setText(newLines);
                    }

                } else {
                    Log.e(TAG, "received an unexpected intent: " + intent.getAction());
                }
            } else {
                Log.e(TAG, "receiver triggered but context or intent was null");
            }
        }
    };

    public void startCronet() {
        Log.w(TAG, "no-op");
    }

    private void submitDirectUrl(String urlToSubmit) {
        ArrayList<String> testUrls = new ArrayList<String>();
        ArrayList<String> directUrls = new ArrayList<String>();
        directUrls.add(urlToSubmit);
        submit(testUrls, directUrls);
    }

    private void submitTestUrl(String urlToSubmit) {
        ArrayList<String> testUrls = new ArrayList<String>();
        ArrayList<String> directUrls = new ArrayList<String>();
        testUrls.add(urlToSubmit);
        submit(testUrls, directUrls);
    }

    private void submit(ArrayList<String> testUrls, ArrayList<String> directUrls) {

        // clear result window
        mResultTextView.setText("waiting for result...");

        ArrayList<String> emptyList = new ArrayList<String>();

        NetworkIntentService.submit(
                this,
                testUrls,
                directUrls,
                null,
                emptyList,
                1,
                1,
                1
        );
    }
}
