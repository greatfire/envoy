package main

import (
	"log"
	"net/http"

	http_signature_auth "github.com/francoismichel/http-signature-auth-go"
)

type ConcealedAuthHandler struct {
	keysDB http_signature_auth.Keys
	handler http.Handler
}

func (h ConcealedAuthHandler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	log.Println("Concealed Auth Handler", r.Proto)

	if r.Method == http.MethodConnect {
		log.Println("verifiying CONNECT signature")
		ok, err := http_signature_auth.VerifySignature(&h.keysDB, r)
		if err != nil {
			log.Printf("Signature validation error: %v\n", err)
			http.Error(w, "Not Found", http.StatusNotFound)
			return
		}

		if !ok {
			log.Printf("Unauthorized request from %s", r.RemoteAddr)
			http.Error(w, "Not Found", http.StatusNotFound)
			return
		}


		log.Println("🎸 CA success")
		h.handler.ServeHTTP(w, r)
		return
	} else if r.Method == http.MethodGet ||
	          r.Method == http.MethodHead ||
	          r.Method == http.MethodPost ||
	          r.Method == http.MethodPut ||
	          r.Method == http.MethodPatch ||
	          r.Method == http.MethodDelete ||
	          r.Method == http.MethodOptions ||
	          r.Method == http.MethodTrace {
		log.Printf("Method not CONNECT: %s\n", r.Method)
		http.Error(w, "Not Found", http.StatusNotFound)
		return
	}

	log.Printf("Passing HTTP/2 request...", r.Method)
	// else, this is HTTP/2 stuff, pass it though
	h.handler.ServeHTTP(w, r)
}
