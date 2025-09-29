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
	log.Println("Concdealed Auth Request")

	if r.Method == http.MethodConnect {
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
	} else {
		log.Printf("Method not CONNECT: %s\n", r.Method)
	}

	http.Error(w, "Not Found", http.StatusNotFound)
}
