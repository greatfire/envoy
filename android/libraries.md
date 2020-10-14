[Demo application](https://github.com/greatfire/envoy/tree/master/android/demo/src/main/java/com/example/myapplication)

## HttpURLConnection
1. Crate a CronetEngine.Builder with `new CronetEngine.Builder(context)`.
2. Set envoy URL to the engine: `engineBuilder.setEnvoyUrl("MY_ENVOY_URL")`.
3. Build an engine by calling `engineBuilder.build()`.
4. Cast the result to HttpURLConnection type after invoking `engine.openConnection(url)`.

## Retrofit
1. Create the retrofit client using the retrofit service and build the client.
2. While building the retrofit client, use OkHttpClient for the interceptor.
3. Create CronetEngine object, set the envoy URL to the engine, and create CronetInterceptor
using the engine.
4. Add the CronetInterceptor to your OkHttpClient using addInterceptor() method from the
OkHttpClient class.
5. Create CronetOkHttpCallFactory object using the OkHttpClient and pass this object to the
callFactory() method of the retrofit client.
6. Now your retrofit client will intercept and create a proxy between your HTTP connection and
every network call that the client provides.
