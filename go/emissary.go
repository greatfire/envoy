package emissary
/*
	This is the Go helper code that's part of Envoy

*/

import (
	"encoding/base64"
	"fmt"
	"net"
	"net/http"
	"net/url"

	ndns "github.com/ncruces/go-dns"
	"go.uber.org/zap"
	"IEnvoyProxy"
)

type Emissary struct {
	EnableLogging	bool
	UnsafeLogging	bool
	LogLevel		string

	TestTarget		string
	TargetRespose	int

	DOHServer		string

	ProxyListen		string

	Salt			string

	Controller		*IEnvoyProxy.Controller

	envoyUrl		string
	envoyHost		string
	echConfigList	[]byte
}

type EnvoyResonse struct {
	EnvoyUrl	string
}

type Stopper struct {
    foo bool
}

func (s Stopper) Stopped(name string, err error) {
    fmt.Printf("stopped %s", name)
}


func NewEmissary() (*Emissary) {
	e := &Emissary{
		EnableLogging: true,
		UnsafeLogging: true,
		LogLevel: "DEBUG",

		TestTarget: "https://www.google.com/generate_204",
	    TargetRespose: 204,

	    DOHServer: "9.9.9.9",

	    // XXX should we re-use the code in IEP to find a free port?
	    ProxyListen: "27629",

		Salt: randStringBytes(16),
	}

	return e
}

// Set the Envoy URL used by the proxy
// echConfigList must be looked up from DNS
//
// enovyUrl: Envoy URL to proxy to
// echConfigList: Base64 encoded ECH data for the URL's domain,
//     Passing an empty string is fine, but ECH won't work
//
func (e *Emissary) SetEnvoyUrl(envoyUrl, echConfigListStr string) {
	// TODO support envoy:// urls?

	zap.S().Debugf("Setting envoyUrl: %s echConfigListStr: %s")

    echConfigList, err := base64.StdEncoding.DecodeString(echConfigListStr)
    if err != nil {
    	zap.S().Errorf("error decoding echConfigList string, ECH disabled")
    	echConfigList = make([]byte, 0, 0)
    }

	e.envoyUrl = envoyUrl
	e.echConfigList = echConfigList

	// parse out the host name so we don't have to do it on every request
    u, err := url.Parse(envoyUrl)
    if err != nil {
        zap.S().Errorf("error parsing envoy host name from URL %s", err)
        return
    }
    e.envoyHost = u.Hostname()
}

// set the default Go resolver to use DNS over HTTPS with our
// working server
func (e *Emissary) setDOHServer() {
	zap.S().Infof("Setting default Go DNS resolver to use DOH: %s", e.DOHServer)
	doh_url := "https://" + e.DOHServer + "/dns-query{?dns}"
	resolver, r_err := ndns.NewDoHResolver(doh_url)
	if r_err != nil {
		zap.S().Fatalf("Failed to make a resolver: %s", r_err)
	}
	net.DefaultResolver = resolver
}

// start up the web server for the Envoy proxy proxy
//
func (e *Emissary) startWebServer() {
	http.HandleFunc("/envoy", e.envoyHandler)
	http.HandleFunc("/envoy3", e.envoy3Handler)

	s := http.Server{
		Addr:    e.ProxyListen,
	}

	zap.S().Infof("Emissary proxy listening on: %s", e.ProxyListen)

	s.ListenAndServe()
}

// Initialize Emissary
//
//
func (e *Emissary) Init(tempDir string) {

	// this is some hacky log configuration 😆 XXX
	cfg := zap.NewDevelopmentConfig()
	// cfg.Development = false
	// cfg.Level.SetLevel(zap.InfoLevel)
	logger, _ := cfg.Build()
    zap.ReplaceGlobals(logger)
    defer logger.Sync()

    // Create the IEnvoyProxy Controller
    s := Stopper{foo: false}
    e.Controller = IEnvoyProxy.NewController(
    	tempDir, e.EnableLogging, e.UnsafeLogging, e.LogLevel, s)

    // Set the default DNS server for Go stuff
    e.setDOHServer()
}

func (e *Emissary) FindEnvoyUrl() (string) {
	// Test our Envoy proxy proxy, it uses ECH to proxy to an upstream
    // Envoy proxy

    zap.S().Debugf("Testing Envoy via %s", e.envoyUrl)

    proxyEnvoyUrl := e.testHttps()
    return proxyEnvoyUrl
}
