package com.example.myapplication;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class ShadowsocksServiceReceiver extends BroadcastReceiver {

    private static final String TAG = "SSReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent.getAction().equals("com.greatfire.envoy.SS_LOCAL_STARTED")) {
            String localAddress = intent.getStringExtra("com.greatfire.envoy.SS_LOCAL_STARTED.LOCAL_ADDRESS");
            if (localAddress == null) {
                localAddress = "127.0.0.1";
            }
            int localPort = intent.getIntExtra("com.greatfire.envoy.SS_LOCAL_STARTED.LOCAL_PORT", 1080);
            // you can start your own background services here to use the socks5 service
            Log.d(TAG, String.format("Shadowsocks service started at %s:%d", localAddress, localPort));
        }
    }
}