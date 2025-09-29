package main

import (
	"crypto"
	"encoding/base64"
	"errors"
	"log"
	"net"
	"net/http"
	"net/url"
	"strings"

	http_signature_auth "github.com/francoismichel/http-signature-auth-go"
)

type EnvoyProxy struct {
	ProxyListen string
	keysDB http_signature_auth.Keys
	config Config
}

func (e *EnvoyProxy) AddKey(id http_signature_auth.KeyID, key crypto.PublicKey) crypto.PublicKey {
	return e.keysDB.AddKey(id, key)
}

func (e *EnvoyProxy) isRequestAllowed(host string) bool {
	log.Printf("☑️ host: %s\n", host)

	if len(e.config.AllowedHosts.Match) > 0 {
		for _, allowedHost := range e.config.AllowedHosts.Match {
			log.Printf("checking match: %s\n", allowedHost)
			if host == allowedHost {
				log.Println("✅")
				return true
			}
		}
	}

	if len(e.config.AllowedHosts.EndsWith) > 0 {
		for _, allowedSuffix := range e.config.AllowedHosts.EndsWith {
			log.Printf("checking suffix: %s", allowedSuffix)
			if strings.HasSuffix(host, allowedSuffix) {
				log.Println("✅")
				return true
			}
		}
	}

	log.Println("❌")
	return false
}

func (e *EnvoyProxy) proxyRequest(orig_req *http.Request) (*http.Response, error) {

	u := orig_req.Header["Url-Orig"][0]

	log.Printf("🧚‍♂️ Making request to %s\n", u)

	req, err := http.NewRequest(orig_req.Method, u, nil)
	if err != nil {
		log.Printf("error creating request: %s", err)
		return &http.Response{}, err
	}

	// Copy over all the headers
	for name, values := range orig_req.Header {
		// skip Host-Orig and Url-Orig and Authorization
		switch name {
		case "Url-Orig":
			// skip
		case "Host-Orig":
			// skip
		case "Authorization":
			// skip
		case "X-Sig-Auth-Material":
			// skip
		default:
			for _, value := range values {
				req.Header.Add(name, value)
			}
		}
	}

	httpClient := http.Client{}

	resp, err := httpClient.Do(req)
	if err != nil {
		log.Printf("Error requesting proxy: %s", err)
		return &http.Response{}, err
	}

	return resp, nil
}


// assumes the request contains the X-Sig-Auth-Material header provided by
// https://gitlab.com/guardianproject/developer-libraries/http-concealed-auth
func (e *EnvoyProxy) validateConcealedAuthHeader(r *http.Request) (bool, error) {

	// log.Printf("X-Sig-Auth-Material: %s", r.Header["X-Sig-Auth-Material"])

	if len(r.Header["X-Sig-Auth-Material"]) != 1 {
		return false, errors.New("bad or no X-Sig-Auth-Material header")
	}

	encoded_material := r.Header["X-Sig-Auth-Material"][0]
	decoded, err := base64.URLEncoding.DecodeString(encoded_material)
	if err != nil {
		return false, err
	}

	material := http_signature_auth.NewTLSExporterMaterial(decoded)

	signatureCandidate, err := http_signature_auth.ExtractSignature(r)
	if err != nil {
		return false, err
	}

	if signatureCandidate == nil {
		return false, errors.New("Missing auth header")
	}

	return http_signature_auth.VerifySignatureWithMaterial(&e.keysDB, signatureCandidate, &material)
}

//
func (e *EnvoyProxy) envoyProxyHandler(w http.ResponseWriter, r *http.Request) {
	// it seems like CONNECT and TRACE are the only methods we should disallow
	if r.Method == "CONNECT" || r.Method == "TRACE" {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	// first, validate the Concealed Auth header
	// return a 404 otherwise, and let the upstream nginx send the user elsewhere
	valid, err := e.validateConcealedAuthHeader(r)
	if err != nil || valid == false {
		log.Printf("Auth header failure: %v", err)
		// generic 404 on failure
		http.Error(w, "Not Found", http.StatusNotFound)
		return
	}

	// The Url-Orig header is required for us to proxy.
	// The client should send a "Host-Orig" header as well, but we don't
	// need that
	targetUrl := r.Header["Url-Orig"]
	if len(targetUrl) == 0 {
		log.Printf("Request with no proxy headers")
		// generic 404 on failure
		http.Error(w, "Bad Request", http.StatusBadRequest)
		return
	}

	u, err := url.Parse(targetUrl[0])
	if err != nil {
		log.Println("error paring target URL")
		http.Error(w, "Bad Request", http.StatusBadRequest)
		return
	}
	host, _, err := net.SplitHostPort(u.Host)
	if err != nil {
		log.Println("error splitting host and port?")
		http.Error(w, "Bad Request", http.StatusBadRequest)
		return
	}

	if !e.isRequestAllowed(host) {
		http.Error(w, "Bad Request", http.StatusBadRequest)
		return
	}

	resp, err := e.proxyRequest(r)
	if err != nil {
		log.Printf("Error proxying request %v", err)
		http.Error(w, "Error proxying request", http.StatusInternalServerError)
		return
	}

	err = copyResponse(w, resp)
	if err != nil {
		log.Printf("Error copying response %s", err)
		http.Error(w, "Error copying response", http.StatusInternalServerError)
		return
	}
}
