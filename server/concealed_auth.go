package main

import (
	"encoding/base64"
	"errors"
	"log"
	"net/http"

	http_signature_auth "github.com/francoismichel/http-signature-auth-go"
)

type ConcealedAuthHandler struct {
	keysDB http_signature_auth.Keys
	handler http.Handler
}

func (h ConcealedAuthHandler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	log.Println("Concdealed Auth Request")

	if r.Method == http.MethodConnect {
		valid, err := h.validateConcealedAuthHeader(r)
		if err != nil || valid == false {
			log.Printf("Auth header failuer: %v", err)
			http.Error(w, "Not Found", http.StatusNotFound)
			return
		}

		log.Println("🎸 CA success")
		h.handler.ServeHTTP(w, r)
		return
	}

	http.Error(w, "Not Found", http.StatusNotFound)
}

// assumes the request contains the X-Sig-Auth-Material header provided by
// https://gitlab.com/guardianproject/developer-libraries/http-concealed-auth
func (h ConcealedAuthHandler) validateConcealedAuthHeader(r *http.Request) (bool, error) {

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

	return http_signature_auth.VerifySignatureWithMaterial(&h.keysDB, signatureCandidate, &material)
}