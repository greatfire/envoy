package main

import (
	"crypto/x509"
	// "crypto/tls"
	"log"
	"net/http"
	// "net/http/httputil"
	// "net/url"
	"os"
	"golang.org/x/crypto/ssh"
	// "golang.org/x/net/http2"

	http_signature_auth "github.com/francoismichel/http-signature-auth-go"
)

func startEnvoyProxy(config Config, keysDB http_signature_auth.Keys) {
	e := EnvoyProxy{
		ProxyListen: config.Listen,
		config: config,
		keysDB: &keysDB,
	}

	envoyMux := http.NewServeMux()

	envoyMux.HandleFunc("/", e.envoyProxyHandler)

	s := http.Server{
		Addr:    e.ProxyListen,
		Handler: envoyMux,
	}

	log.Printf("Envoy Go proxy listening on: %s\n", e.ProxyListen)

	s.ListenAndServe()
}

func startMasqueProxy(config Config, keysDB http_signature_auth.Keys) {
	log.Printf("MASQUE proxy listening on: :18989\n")

	proxy := NewMasqueProxy(&keysDB)
	proxy.UpstreamServer = "localhost"
	proxy.UpstreamPort = 7676
	proxy.Start()

	// backUrl, err := url.Parse("https://localhost:7676/")
	// if err != nil {
	// 	log.Fatalf("Error parsing backend URL: %v\n", err)
	// }

	// var protocols http.Protocols
	// protocols.SetUnencryptedHTTP2(true)

	// // proxy := httputil.NewSingleHostReverseProxy(backUrl)
	// proxy := &httputil.ReverseProxy{
	// 	Rewrite: func(r *httputil.ProxyRequest) {
	// 		r.SetURL(backUrl)
	// 	},
	// 	Transport: &http.Transport{
	// 		ForceAttemptHTTP2: true,
	// 		Protocols:         &protocols,
	// 		TLSClientConfig:   &tls.Config{InsecureSkipVerify: true},
	// 	},
	// }

	// concealedHandler := ConcealedAuthHandler{
	// 	keysDB: keysDB,
	// 	handler: proxy,
	// }

	// proxyServer := http.Server{
	// 	Addr: ":18989",
	// 	Handler: concealedHandler,
	// }

	// h2Conf := http2.Server{}

	// http2.ConfigureServer(&proxyServer, &h2Conf)

	// proxyServer.ListenAndServeTLS("cert.pem", "key.pem")
}


func main() {
	var keysDB http_signature_auth.Keys;

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
		keysDB.AddKey(http_signature_auth.KeyID(user.Name), pubKey)
	}

	go startEnvoyProxy(config, keysDB)

	startMasqueProxy(config, keysDB)

	log.Println("Exiting")
}
