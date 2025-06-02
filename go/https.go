package emissary
/*
	This part of Emissary provides a proxy that forwards to a "real"
	Envoy proxy, using HTTP/2 or HTTP/3 with ECH

	This mostly exists because Android/Conscrypt doesn't support ECH yet
*/
import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"math/rand"
	"net/http"
	"net/url"
	"time"

	// "github.com/stevenmcdonald/IEnvoyProxy"
	"go.uber.org/zap"
)

// this is from https://stackoverflow.com/questions/22892120/how-to-generate-a-random-string-of-a-fixed-length-in-go
// not the fastest, but it's concise and good enough
const letterBytes = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"

//
// return a random string of bytes of length n
//
func randStringBytes(n int) string {
    b := make([]byte, n)
    for i := range b {
        b[i] = letterBytes[rand.Intn(len(letterBytes))]
    }
    return string(b)
}

//
// return the salted SHA256 hash of the input
//
func (e *Emissary) getDigest(input string) (string) {
	temp := input + e.Salt
	digest := sha256.Sum256([]byte(temp))
	return hex.EncodeToString(digest[:])
}

///
// returns a new http.Request to the target URL with the Envoy headers and
// a cache param
//
func (e *Emissary) getTestRequest(ctx context.Context, u string) (*http.Request, error) {

	req, err := http.NewRequestWithContext(ctx, "GET", u, nil)
	if err != nil {
		zap.S().Errorf("Error creating http request: %s", err)
		return nil, err
	}

	tu, err := url.Parse(e.TestTarget)
	if err != nil {
		zap.S().Errorf("Error parsing TargetUrl: %s, %s", e.TestTarget, err)
		return nil, err
	}

	// add caching param
	digest := e.getDigest(tu.String())
	q := req.URL.Query()
	q.Add("_godigest", digest)
	req.URL.RawQuery = q.Encode()

	// Add Envoy headers
	host := tu.Hostname()
	req.Header.Set("Url-Orig", tu.String())
	req.Header.Set("Host-Orig", host)

	return req, nil
}

///
// do the HTTPS test
// useHttp3 is a bool, HTTP/2 is used if false
// results are sent to the EnvoyResponse chan
//
func (e *Emissary) doTestHttps(ctx context.Context, useHttp3 bool, resultChan chan EnvoyResonse) (bool, error) {
	u := "http://" + e.ProxyListen

	if useHttp3 {
		u += "/envoy3"
	} else {
		u += "/envoy"
	}

	zap.S().Debugf("Testing URL: %s", u)

	testUrl, err := url.Parse(u)
	if err != nil {
		zap.S().Errorf("Error parsing our own URL %s", err)
		return false, err
	}

	req, err := e.getTestRequest(ctx, u)
	if err != nil {
		zap.S().Errorf("error getting HTTP request %s", err)
		return false, err
	}

	client := &http.Client{}
	resp, err := client.Do(req)
	if err != nil {
		if !errors.Is(err, context.Canceled) {
			zap.S().Errorf("Error requesting URL via HTTP", err)
		}
		return false, err
	}

	if resp.StatusCode == e.TargetResponse {
		// clear out digest param
		resultChan <- EnvoyResonse{
			EnvoyUrl: testUrl.String(),
		}
	}
	return false, errors.New("No working URL")
}

///
// Test HTTPS with ECH, trying both HTTP/2 and HTTP/3 in parallel
//
func (e *Emissary) testHttps() (string) {
	// XXX do we need to wait for it to start?
	//
	// e.Controller.TenaciousDnsEnvoyUrl = p.EnvoyUrl
	// e.Controller.Start(IEnvoyProxy.TenaciousDns, "")
	// e.isItUpYet(IEnvoyProxy.TenaciousDns)
	// zap.S().Debug("it's up?")

	ctx, cancel := context.WithCancel(context.Background())
	resultChan := make(chan EnvoyResonse, 1)

	zap.S().Debugf("waiting for proxy to start...")
	e.isItUpYet(e.ProxyListen)


	zap.S().Debug("testing HTTPS...")
	go e.doTestHttps(ctx, true, resultChan)
	go e.doTestHttps(ctx, false, resultChan)

	// Give it all 30 seconds to find a working Envoy URL before giving up
	select {
	case result := <- resultChan:
		cancel()
		zap.S().Infof("*** got a result: %s", result.EnvoyUrl)
		return result.EnvoyUrl
	case <-time.After(30 * time.Second):
		cancel()
		zap.S().Errorf("Timeout testing HTTPS")
		return ""
	}
}
