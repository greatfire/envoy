package com.example.myapplication;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

import org.chromium.net.CronetEngine;
import org.greatfire.envoy.ShadowsocksService;
import org.jetbrains.annotations.NotNull;


public class MainActivity extends FragmentActivity {

    private static final String TAG = "EnvoyDemoApp";

    private ViewPager2 viewPager;

    private static final String[] titles = new String[]{"Cronet", "OKHttp", "WebView", "Volley", "HttpConn", "Nuke"};


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        String ssUri = "ss://Y2hhY2hhMjAtaWV0Zi1wb2x5MTMwNTpwYXNz@127.0.0.1:1234";
        Intent shadowsocksIntent = new Intent(this, ShadowsocksService.class);
        shadowsocksIntent.putExtra("org.greatfire.envoy.START_SS_LOCAL", ssUri);
        shadowsocksIntent.putExtra("org.greatfire.envoy.START_SS_LOCAL.LOCAL_ADDRESS", "127.0.0.1");
        shadowsocksIntent.putExtra("org.greatfire.envoy.START_SS_LOCAL.LOCAL_PORT", 1080);
        ContextCompat.startForegroundService(getApplicationContext(), shadowsocksIntent);

        // https://developer.android.com/guide/components/bound-services#java
        //Intent intent = new Intent(this, ShadowsocksService.class);
        //private ServiceConnection connection = new ServiceConnection() { }
        //bindService(intent, connection, Context.BIND_AUTO_CREATE);

        viewPager = findViewById(R.id.pager);
        // viewPager.setPageTransformer();
        FragmentStateAdapter pagerAdapter = new MyFragmentStateAdapter(this);
        viewPager.setAdapter(pagerAdapter);

        TabLayout tabLayout = findViewById(R.id.tab_layout);
        new TabLayoutMediator(tabLayout, viewPager,
                (tab, position) -> tab.setText(titles[position])
        ).attach();

        // String url = "https://ifconfig.co/ip";
        // url = "https://httpbin.org/ip";
        String envoyUrl = "socks5://127.0.0.1:1080"; // Keep this if no port conflicts

        CronetEngine.Builder engineBuilder = new CronetEngine.Builder(getApplication());
        engineBuilder.setUserAgent("curl/7.66.0");
        engineBuilder.setEnvoyUrl(envoyUrl);

        CronetEngine engine = engineBuilder.build();
        Log.d(TAG, "engine version " + engine.getVersionString());
        //File outputFile = File.createTempFile("cronet", "log",
        //    Environment.getExternalStorageDirectory());
        //engine.startNetLogToFile(outputFile.toString(), false);
/*        Executor executor = Executors.newSingleThreadExecutor();
        CronetFragment.MyUrlRequestCallback callback = new CronetFragment.MyUrlRequestCallback();
        UrlRequest.Builder requestBuilder = engine.newUrlRequestBuilder(
                url, callback, executor);
        UrlRequest request = requestBuilder.addHeader("My-Header1", "value1").build();
        request.start();*/
        // https://stackoverflow.com/questions/41110684/cronet-and-experimentalcronetengine
        // URL.setURLStreamHandlerFactory(new CronetURLStreamHandlerFactory((ExperimentalCronetEngine)engine));

        // new RetrofitRequestTask().execute(url);
    }

    @Override
    public void onBackPressed() {
        if (viewPager.getCurrentItem() == 0) {
            // If the user is currently looking at the first step, allow the system to handle the
            // Back button. This calls finish() on this activity and pops the back stack.
            super.onBackPressed();
        } else {
            // Otherwise, select the previous step.
            viewPager.setCurrentItem(viewPager.getCurrentItem() - 1);
        }
    }

    static class MyFragmentStateAdapter extends FragmentStateAdapter {

        MyFragmentStateAdapter(FragmentActivity fa) {
            super(fa);
        }

        @NotNull
        @Override
        public Fragment createFragment(int position) {
            Log.d("Fragment", "at position " + position);
            switch (position) {
                case 0:
                    return new CronetFragment();
                case 1:
                    return new OkHttpFragment();
                case 2:
                    return new WebViewFragment();
                case 3:
                    return new VolleyFragment();
                case 4:
                    return new HttpURLConnectionFragment();
                case 5:
                    return new SimpleFragment();
                default:
                    return BaseFragment.newInstance(position);
            }
        }

        @Override
        public int getItemCount() {
            return titles.length;
        }

        public static class SimpleFragment extends Fragment {
            public SimpleFragment() {
                // Required empty public constructor
            }

            @Nullable
            @Override
            public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
                LinearLayout linearLayout = new LinearLayout(getContext());
                linearLayout.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));
                linearLayout.setOrientation(LinearLayout.HORIZONTAL);
                linearLayout.setGravity(Gravity.CENTER_VERTICAL | Gravity.CENTER_HORIZONTAL);
                TextView textView = new TextView(getContext());
                // textView.setInputType(EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE);
                textView.setText(R.string.nuke_instruction);
                linearLayout.addView(textView);
                return linearLayout;
            }
        }
    }
}
