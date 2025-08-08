package org.greatfire.envoy

enum class EnvoyTransportType {
    DIRECT,         // direct connection, no proxy
    OKHTTP_ENVOY,   // Use OkHttp via an Envoy proxy
    CRONET_ENVOY,   // Use Cronet via an Envoy proxy
    OKHTTP_MASQUE,  // Use OkHttp via a MASQUE proxy
    CRONET_MASQUE,  // Use Croent via a MASQUE proxy
    OKHTTP_PROXY,   // use OkHttp via a standard (HTTP/SOCKS) proxy
    CRONET_PROXY,   // use Cronet via a standard (HTTP/SOCKS) proxy
    HTTP_ECH,       // use ECH Envoy proxy (provided by Go code for now)
    HYSTERIA2,      // Hysteria2
    V2WS,           // V2Ray via WebSocket
    V2SRTP,         // V2Ray via fake RSTP
    V2WECHAT,       // V2Ray via fake WeChat
    SHADOWSOCKS,    // Shadowsocks
    OHTTP,          // OHTTP
    UNKNOWN         // unknown/undefined, used as an initial value
}