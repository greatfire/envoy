package org.greatfire.envoy

class OkHttpEnvoyTransport(envoyUrl: String, testUrl: String, testResponseCode: Int) : Transport(EnvoyServiceType.OKHTTP_ENVOY, envoyUrl, testUrl, testResponseCode) {
    // nothing to implement?
}