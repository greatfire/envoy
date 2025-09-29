package main

import (
	"crypto"
	"log"
	"net/http"
	"os"
	"golang.org/x/crypto/ssh"

	"github.com/elazarl/goproxy"
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
		config: config,
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

	envoyMux := http.NewServeMux()

	envoyMux.HandleFunc("/", e.envoyProxyHandler)

	s := http.Server{
		Addr:    e.ProxyListen,
		Handler: envoyMux,
	}

	log.Printf("Envoy Go proxy listening on: %s\n", e.ProxyListen)

	go s.ListenAndServe()

	proxy := goproxy.NewProxyHttpServer()
	concealedHandler := ConcealedAuthHandler{
		keysDB: e.keysDB,
		handler: proxy,
	}

	proxyServer := http.Server{
		Addr: ":18989",
		Handler: concealedHandler,
	}

	log.Printf("MASQUE proxy listening on: :18989\n")

	proxyServer.ListenAndServeTLS("cert.pem", "key.pem")
}
