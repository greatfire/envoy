package emissary
/*
	This is the Go helper code that's part of Envoy

*/

import (
	"context"
	"encoding/base64"
	"fmt"
	"net"
	"net/http"
	"net/url"
	"time"

	ndns "github.com/ncruces/go-dns"
	"go.uber.org/zap"
	"IEnvoyProxy"
)

type Emissary struct {
	EnableLogging	bool
	UnsafeLogging	bool
	LogLevel		string

	TestTarget		string
	TargetResponse	int

	ProxyListen		string

	Salt			string

	Controller		*IEnvoyProxy.Controller

	envoyUrl		string
	envoyHost		string
	echConfigList	[]byte

	initialized		bool
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
	    TargetResponse: 204,

	    // DOHServer: "9.9.9.9",

	    // XXX should we re-use the code in IEP to find a free port?
	    ProxyListen: "127.0.0.1:27629",

		Salt: randStringBytes(16),
	}

	return e
}

// Helpers because the JVM code apparently can't access e.Controller directly

func (e *Emissary) StartHysteria2(url string) string {
	e.Controller.Hysteria2Server = url
	e.Controller.Start(IEnvoyProxy.Hysteria2, "")
	return e.Controller.LocalAddress(IEnvoyProxy.Hysteria2)
}

func (e *Emissary) StopHysteria2() {
	e.Controller.Stop(IEnvoyProxy.Hysteria2)
}

func (e *Emissary) StartV2RaySrtp(server, port, uuid string) string {
	e.Controller.V2RayServerAddress = server
	e.Controller.V2RayServerPort = port
	e.Controller.V2RayId = uuid
	e.Controller.Start(IEnvoyProxy.V2RaySrtp, "")

	return e.Controller.LocalAddress(IEnvoyProxy.V2RaySrtp)
}

func (e *Emissary) StopV2RaySrtp() {
	e.Controller.Stop(IEnvoyProxy.V2RaySrtp)
}

func (e *Emissary) StartV2RayWechat(server, port, uuid string) string {
	e.Controller.V2RayServerAddress = server
	e.Controller.V2RayServerPort = port
	e.Controller.V2RayId = uuid
	e.Controller.Start(IEnvoyProxy.V2RayWechat, "")
	return e.Controller.LocalAddress(IEnvoyProxy.V2RayWechat)
}

func (e *Emissary) StopV2RayWechat() {
	e.Controller.Stop(IEnvoyProxy.V2RayWechat)
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

	zap.S().Debugf("Setting envoyUrl: %s echConfigListStr: %s", envoyUrl, echConfigListStr)

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
func (e *Emissary) SetDOHServer(dohServer string) {
	zap.S().Infof("Setting default Go DNS resolver to use DOH: %s", dohServer)
	doh_url := "https://" + dohServer + "/dns-query{?dns}"
	resolver, r_err := ndns.NewDoHResolver(doh_url)
	if r_err != nil {
		zap.S().Fatalf("Failed to make a resolver: %s", r_err)
	}
	net.DefaultResolver = resolver
}

// start up the web server for the Envoy proxy proxy
//
func (e *Emissary) startWebServer() {
	zap.S().Debugf("starting web server...")

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

	zap.S().Debugf("Init called with tempDir %s", tempDir)

	// XXX WTF is going on here?
	if (e.initialized) {
		zap.S().Errorf("we're being Initialized twice")
		return
	}
	e.initialized = true

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
    e.startWebServer()
}

func (e *Emissary) FindEnvoyUrl() (string) {
	// Test our Envoy proxy proxy, it uses ECH to proxy to an upstream
    // Envoy proxy

    zap.S().Debugf("Testing Envoy via %s", e.envoyUrl)

    proxyEnvoyUrl := e.testHttps()
    return proxyEnvoyUrl
}

///
// Attempt a basic TCP connection to see if a service is up yet
// blocks and polls until the connection is made
//
// XXX this probably should have some kind of timeout for the service
// failing to start
//
func (e *Emissary) isItUpYet(addr string) (bool, error) {

	var d net.Dialer

	// poll every 2 seconds until the service is listening
	up := false
	for !up {
		ctx, cancel := context.WithTimeout(context.Background(), 2 * time.Second)
		conn, err := d.DialContext(ctx, "tcp", addr)
		// This can throw timeout and connection refused... probably more
		// just ignore it all ;-)
		zap.S().Debugf("&&&& isItUpYet %s", err)
		if err == nil {
			conn.Close()
			cancel()
			return true, nil
		}

		time.Sleep(2 * time.Second)
	}

	return false, nil
}