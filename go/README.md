
Emissary
========

Go library for Envoy

This is currently only used in the Android version of Envoy, but there's nothing Android specific about it

Currently it provides:

* Envoy proxy proxy - that is an Envoy proxy that forwards request to a "real" Envoy proxy, using ECH, over HTTP/2 or HTTP/3.

Given a URL to an Envoy proxy, it will make a test request using both HTTP/2 and HTTP/3, the first one that succeeds is used. Optionally, the caller can set Emissary.DOHServer to an unblocked server for the Go code to use. ECH information for the upstream Envoy server from DNS must be provided as well.

* IEnvoyProxy - this includes IEnvoyProxy for Go based PTs. Because of the way gomobile works, JVM code can't access Emissary.Controller directly 🙄, so some helper functions are included to call Start and Stop, etc.

--

IEnvoyProxy is shared among several projects now, so this is a home for expirements and Envoy speicifc Go code.

It's called "Emissary" because that's a word related to "Envoy", and it would be confusing at best if this also called itself "Envoy".