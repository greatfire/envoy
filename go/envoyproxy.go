package emissary

/*
	proxy requests to a "real" Envoy proxy using ECH and HTTP/2 or HTTP/3

*/

import (
	"crypto/tls"
	"net/http"
	"io"
	"strings"

	"github.com/quic-go/quic-go/http3"
	"go.uber.org/zap"
)

func (e *Emissary) envoyProxyRequest(r *http.Request, useHttp3 bool) (*http.Response, error) {

	u := e.envoyUrl + "?" + r.URL.RawQuery

	req, err := http.NewRequest(r.Method, u, nil)
	if err != nil {
		zap.S().Errorf("error createing request: %s", err)
		return &http.Response{}, err
	}

	// Copy over all the headers
	for name, values := range r.Header {
		for _, value := range values {
			req.Header.Add(name, value)
		}
	}

	tlsClientConfig := &tls.Config {
		EncryptedClientHelloConfigList: e.echConfigList,
		// XXX Go knows it's making a request to the proxy, but it's getting
		// a certificate for wikipedia
		InsecureSkipVerify: true,
	}

	var httpClient http.Client
	if useHttp3 {
		httpClient = http.Client{
			Transport: &http3.Transport{
				TLSClientConfig: tlsClientConfig,
			},
		}
	} else {
		httpClient = http.Client{
			Transport: &http.Transport{
				TLSClientConfig: tlsClientConfig,
			},
		}
	}

	resp, err := httpClient.Do(req)
	if err != nil {
		zap.S().Errorf("Error requesting proxy: %s", err)
		return &http.Response{}, err
	}

	if resp.TLS.ECHAccepted {
		zap.S().Info("envoyECH worked ✅")
	} else {
		zap.S().Info("envoyECH failed ❌")
	}

	return resp, nil
}

// borrowed from github.com/elazarl/goproxy
type flushWriter struct {
	w io.Writer
}

func (fw flushWriter) Write(p []byte) (int, error) {
	n, err := fw.w.Write(p)
	if f, ok := fw.w.(http.Flusher); ok {
		// only flush if the Writer implements the Flusher interface.
		f.Flush()
	}

	return n, err
}


func copyResponse(w http.ResponseWriter, resp *http.Response) (error) {
	for k, vs := range resp.Header {
		// direct assignment to avoid canonicalization
		w.Header()[k] = append([]string(nil), vs...)
	}
	w.WriteHeader(resp.StatusCode)

	// borrowed from github.com/elazarl/goproxy
	var copyWriter io.Writer = w
	// Content-Type header may also contain charset definition, so here we need to check the prefix.
	// Transfer-Encoding can be a list of comma separated values, so we use Contains() for it.
	if strings.HasPrefix(w.Header().Get("content-type"), "text/event-stream") ||
		strings.Contains(w.Header().Get("transfer-encoding"), "chunked") {
		// server-side events, flush the buffered data to the client.
		copyWriter = &flushWriter{w: w}
	}

	nr, err := io.Copy(copyWriter, resp.Body)
	if err := resp.Body.Close(); err != nil {
		zap.S().Warnf("Can't close response body %v", err)
		return err
	}
	zap.S().Debugf("Copied %v bytes to client error=%v", nr, err)
	return nil
}


// HTTP/2 to Envoy proxy
func (e *Emissary) envoyHandler(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" && r.Method != "POST" {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	resp, err := e.envoyProxyRequest(r, false)
	if err != nil {
		zap.S().Errorf("Error proxying request %s", err)
		http.Error(w, "Proxy Error", http.StatusInternalServerError)
		return
	}

	err = copyResponse(w, resp)
	if err != nil {
		zap.S().Errorf("Error copying response %s", err)
		http.Error(w, "Proxy Error", http.StatusInternalServerError)
		return
	}
}

// HTTP/3 to Envoy proxy
func (e *Emissary) envoy3Handler(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" && r.Method != "POST" {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	resp, err := e.envoyProxyRequest(r, true)
	if err != nil {
		zap.S().Errorf("Error proxying request %s", err)
		http.Error(w, "Proxy Error", http.StatusInternalServerError)
		return
	}

	err = copyResponse(w, resp)
	if err != nil {
		zap.S().Errorf("Error copying response %s", err)
		http.Error(w, "Proxy Error", http.StatusInternalServerError)
		return
	}
}
