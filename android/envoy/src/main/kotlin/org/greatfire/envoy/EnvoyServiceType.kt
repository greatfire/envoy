package org.greatfire.envoy

enum class EnvoyServiceType {
    DIRECT,
    OKHTTP_ENVOY,
    CRONET_ENVOY,
    OKHTTP_PROXY,
    CRONET_PROXY,
    HTTP_ECH,
    HYSTERIA2,
    V2WS,
    V2SRTP,
    V2WECHAT,
    UNKNOWN
}