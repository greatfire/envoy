package main

import (
	"crypto"
	"log"
	"net/http"
	"os"
	"golang.org/x/crypto/ssh"

	"github.com/francoismichel/http-signature-auth-go"
)

func main() {
	if len(os.Args) < 2 {
		log.Printf("Usage: %s [config_path]\n", os.Args[0])
		return
	}

	config, err := getConfig(os.Args[1])
	if err != nil {
		log.Fatalf("Error reading config file: %v\n", err)
	}

	log.Printf("Users: %d\n", len(config.Users))

	if config.Listen == "" || len(config.Users) == 0 {
		log.Fatalln("config error")
	}

	e := EnvoyProxy{
		ProxyListen: config.Listen,
	}

	for _, user := range config.Users {
		temp, _, _, _, err := ssh.ParseAuthorizedKey([]byte(user.Key))
		if err != nil {
			log.Printf("Error parsing key for %v: %v\n", user.Name, err)
		}

		pubKey, ok := temp.(crypto.PublicKey)
		if !ok {
			log.Printf("Failed casting key for %v: %v\n", user.Name, err)
		}

		log.Printf("Adding user: %s", user.Name)
		e.AddKey(http_signature_auth.KeyID(user.Name), pubKey)
	}

	http.HandleFunc("/", e.envoyProxyHandler)

	s := http.Server{
		Addr:    e.ProxyListen,
	}

	log.Printf("Envoy Go proxy listening on: %s\n", e.ProxyListen)

	s.ListenAndServe()
}
