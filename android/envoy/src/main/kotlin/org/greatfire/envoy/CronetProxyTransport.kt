package org.greatfire.envoy

class CronetProxyTransport(envoyUrl: String, testUrl: String, testResponseCode: Int) : Transport(EnvoyServiceType.CRONET_PROXY, envoyUrl, testUrl, testResponseCode) {
    // nothing to implement?
}