package main

import (
	"crypto/x509"
	"log"
	"net/http"
	// "net/http/httputil"
	// "net/url"
	"os"
	"golang.org/x/crypto/ssh"
	"golang.org/x/net/http2"

	"github.com/elazarl/goproxy"
	http_signature_auth "github.com/francoismichel/http-signature-auth-go"
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
		// Do this little dance to convert SSH key.
		// we apparetnly can't just pass an ssh.PrivateKey instance here
		temp, _, _, _, err := ssh.ParseAuthorizedKey([]byte(user.Key))
		if err != nil {
			log.Printf("Error parsing key for %v: %v\n", user.Name, err)
			continue
		}

		cryptoPubKey := temp.(ssh.CryptoPublicKey).CryptoPublicKey()

		derBytes, err := x509.MarshalPKIXPublicKey(cryptoPubKey)
		if err != nil {
			log.Printf("Error Marshaling DER %s\n", err)
			continue
		}

		pubKey, err := x509.ParsePKIXPublicKey(derBytes)
		if err != nil {
			log.Printf("Error Parsing DER %s\n", err)
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
	proxy.AllowHTTP2 = true

	// backUrl, err := url.Parse("http://localhost:7676/")
	// if err != nil {
	// 	log.Fatalf("Error parsing backend URL: %v\n", err)
	// }

	// var protocols http.Protocols
	// protocols.SetUnencryptedHTTP2(true)

	// proxy := httputil.NewSingleHostReverseProxy(backUrl)
	// proxy := &httputil.ReverseProxy{
	// 	Rewrite: func(r *httputil.ProxyRequest) {
	// 		r.SetURL(backUrl)
	// 	},
	// 	Transport: &http.Transport{
	// 		ForceAttemptHTTP2: true,
	// 		Protocols:         &protocols,
	// 	},
	// }

	concealedHandler := ConcealedAuthHandler{
		keysDB: e.keysDB,
		handler: proxy,
	}

	proxyServer := http.Server{
		Addr: ":18989",
		Handler: concealedHandler,
	}

	h2Conf := http2.Server{}

	http2.ConfigureServer(&proxyServer, &h2Conf)

	log.Printf("MASQUE proxy listening on: :18989\n")

	proxyServer.ListenAndServeTLS("cert.pem", "key.pem")

	log.Println("Exiting")
}
