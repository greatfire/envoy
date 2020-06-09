1. Download cronet.aar  
   Cronet.aar has the sample API as Google's cronet(except for one extra method `setEnvoyUrl`).
   Download the aar release [cronet-release-v1.0.0.aar](https://en.greatfire.org/demos/cronet-release.aar).
2. **Optional**  
   for apps which use OkHttp 3.12.x(not OkHttp 4.x), patch the `android/envoy` directory by cd into it first,
   then run `patch --forward --force -p1 --reject-file=- < envoy3.patch`.  
   So is the case for Wikipedia which uses OkHttp 3.14.0 .
3. Build or download envoy.aar, see `android/build-envoy.sh`  
   Envoy is the bootstrapping library that has adapters for serval popular libraries(OkHttp/Retrofit, Volley, etc).
   - Import the sub `android` directory as an android project, then put cronet.aar to `android/cronet/cronet-$BUILD.aar`.
      BUILD is debug or release which depends on your build variant.
   - Or just download the aar release [envoy-release-v1.0.0.aar](https://en.greatfire.org/demos/envoy-release.aar) or
   [cronet-okhttp3-v1.0.0.aar](https://en.greatfire.org/demos/envoy-okhttp3-release.aar).
4. Update Wikipedia source code, see `apps/apps-android-wikipedia.patch`.
    1. add aar configurations to app/build.gradle.
    2. call `CronetNetworking.initializeCronetEngine(getApplicationContext(), "YOUR-ENVOY-URL")` in the main/base class' `onCreate` method, i.e. `org.wikipedia.activity.BaseActivity`.  
    `YOUR-ENVOY-URL` is the URL for your CDN or backend server if no CDN is configured(not recommended), such as `https://example.com/". [Here](../native/README.md#examples) for more about envoy url formats.
    3. In Wikipedia only retrofit and OkHttp are used. So add `CronetInterceptor` interceptor  to `ServiceFactory.createRetrofit` and `OkHttpConnectionFactory.OkHttpConnectionFactory`.
5. Setup CDN and backend server, see `apps/nginx.conf`.
   1. Configure CDN [Origin server](https://www.cloudflare.com/learning/cdn/glossary/origin-server/) to your backend server.
   2. Add nginx site config as this:
   ```
    server {
        listen 443 ssl http2;

        ssl_certificate /path/to/example.com.pem;
        ssl_certificate_key /path/to/example.com.key.pem;
        ssl_dhparam /path/to/dhparam.pem;

        location / {
            proxy_ssl_server_name on;
            proxy_pass $http_url_orig;
            proxy_set_header Host $http_host_orig;
            proxy_pass_request_headers on;
        }
    }
    ```
