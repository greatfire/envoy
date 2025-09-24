package org.greatfire.envoy.transport

import org.greatfire.envoy.EnvoyTransportType

class ConcealedAuthTransport(url: String) : Transport(EnvoyTransportType.HTTPCA_ENVOY, url)

// we don't need to do anything special here, concealed auth
// is handled by a NetworkInterceptor
