package main

import (
	"crypto"
	// "crypto/x509"
	// "encoding/pem"
	"log"
	"net/http"
	"os"
	"golang.org/x/crypto/ssh"

	// "github.com/francoismichel/http-signature-auth-go"
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

// 	pub_key_raw := []byte(`-----BEGIN PUBLIC KEY-----
// MCowBQYDK2VwAyEArSM6OHOTdS9oOaxbvv8UittGmD7eLeU6afEgbg5RvQw=
// -----END PUBLIC KEY-----`)


// 	block, _ := pem.Decode(pub_key_raw)
// 	if block == nil || block.Type != "PUBLIC KEY" {
// 		log.Fatalln("PEM Decode error")
// 	}

// 	temp, err := x509.ParsePKIXPublicKey(block.Bytes)
// 	if err != nil {
// 		log.Fatalln("Error getting public key")
// 	}

	pubKeyRaw := []byte("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIK0jOjhzk3UvaDmsW77/FIrbRpg+3i3lOmnxIG4OUb0M support@greatfire.org")

	temp, _, _, _, err := ssh.ParseAuthorizedKey(pubKeyRaw)
	if err != nil {
		log.Fatalln("can't Parse SSH key")
	}

	pubKey, ok := temp.(crypto.PublicKey)
	if !ok {
		log.Fatalln("failed to cast PublicKey\n")
	}

	e.AddKey("envoy", pubKey)

	http.HandleFunc("/", e.envoyProxyHandler)

	s := http.Server{
		Addr:    e.ProxyListen,
	}

	log.Printf("Envoy Go proxy listening on: %s\n", e.ProxyListen)

	s.ListenAndServe()
}
