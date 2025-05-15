package emissary
/*

OkHttp only supports http (not https!) proxies for now
https://github.com/square/okhttp/issues/8373
Cronet does, but this puts the MASQUE connection more in our control

This provides an HTTP CONNECT proxy on localhost that can connect to an
upstream MASQUE server

This is really close to being the example code from:
https://github.com/Invisv-Privacy/masque/blob/main/example/relay-http-proxy/main.go

converted in to a package

*/


import (
	"bufio"
	"errors"
	"fmt"
	"net"
	"net/http"
	"io"
	"log/slog"
	"os"
	"strconv"
	"strings"
	"sync"
	"syscall"

	"go.uber.org/zap"
	masque "github.com/invisv-privacy/masque"
	masqueH2 "github.com/invisv-privacy/masque/http2"
)

type EnvoyMasqueProxy struct {
	// MASQUE server hostname
	UpstreamServer string
	// MASQUE server port
	UpstreamPort int

	ListenPort int

	relayClient *masqueH2.Client

	// not sure if we need these
	token string
	insecure bool
	certData []byte
}

func NewMasqueProxy() (* EnvoyMasqueProxy) {
	p := &EnvoyMasqueProxy{
		UpstreamServer: "masque.smcdonald.us",
		UpstreamPort: 4443,
		ListenPort: 27630,
		insecure: false,
		token: "SOME FAKE VALUE",
	}

	return p
}

func (p *EnvoyMasqueProxy) Start() {

	// Listen for proxy requests
	host := fmt.Sprintf("127.0.0.1:%d", p.ListenPort)
	zap.S().Debugf("MASQUE proxy listening on %s", host)
	l, err := net.Listen("tcp", host)
	if err != nil {
		zap.S().Errorf("Listen error %v", err)
		return
	}

	defer func() {
		if err := l.Close(); err != nil {
			zap.S().Error("Error closing l", "err", err)
		}
	}()

	zap.S().Debug("About to connect to upstream")
	c, err := p.connectToRelay(p.certData)
	if err != nil {
		zap.S().Errorf("Error connecting to relay %s", err)
	}
	p.relayClient = c
	zap.S().Debug("Connected to upstream")

	for {
		conn, err := l.Accept()
		if err != nil {
			zap.S().Error("Couldn't accept client connection", "err", err)
			continue
		}

		go p.handleReq(conn)
	}
}

func transfer(destination io.WriteCloser, source io.ReadCloser, wg *sync.WaitGroup) {
	defer wg.Done()
	n, err := io.Copy(destination, source)
	if err != nil {
		if errors.Is(err, syscall.ECONNRESET) || errors.Is(err, syscall.EPIPE) || errors.Is(err, io.ErrClosedPipe) {
			zap.S().Debug("Connection closed during io.Copy", "err", err, "n", n)
		} else {
			zap.S().Error("Error calling io.Copy", "err", err, "n", n)
		}
	} else {
		zap.S().Debug("Successfully transfered", "n", n)
	}
}

// handleConnectMasque handles a CONNECT request to the proxy and returns the connected stream upon success.
func (p *EnvoyMasqueProxy) handleConnectMasque(c net.Conn, req *http.Request) *masqueH2.Conn {
	// logger = logger.With("req", req)
	disallowedRes := &http.Response{
		StatusCode: http.StatusUnauthorized,
		ProtoMajor: 1,
		ProtoMinor: 1,
	}

	_, port, err := net.SplitHostPort(req.URL.Host)
	if err != nil {
		zap.S().Error("Failed to split host and port", "err", err)
		err := disallowedRes.Write(c)
		if err != nil {
			zap.S().Error("Error calling disallowedRes.Write", "err", err)
		}
		if err := c.Close(); err != nil {
			zap.S().Error("Error closing c", "err", err)
		}
		return nil
	}

	portInt, err := strconv.Atoi(port)
	if err != nil {
		zap.S().Error("Failed to convert port to int", "err", err)
		err := disallowedRes.Write(c)
		if err != nil {
			zap.S().Error("Error calling disallowedRes.Write", "err", err)
		}
		if err := c.Close(); err != nil {
			zap.S().Error("Error closing c", "err", err)
		}
		return nil
	}

	if masque.IsDisallowedPort(uint16(portInt)) {
		zap.S().Error("Disallowed port", "port", port)
		err := disallowedRes.Write(c)
		if err != nil {
			zap.S().Error("Error calling disallowedRes.Write", "err", err)
		}
		if err := c.Close(); err != nil {
			zap.S().Error("Error closing c", "err", err)
		}
		return nil
	}

	masqueConn, err := p.relayClient.CreateTCPStream(req.URL.Host)
	if err != nil {
		zap.S().Error("Failed to create TCP stream", "err", err)
		err := disallowedRes.Write(c)
		if err != nil {
			zap.S().Error("Error calling disallowedRes.Write", "err", err)
		}
		if err := c.Close(); err != nil {
			zap.S().Error("Error closing c", "err", err)
		}
		return nil
	}

	return masqueConn
}


func (p *EnvoyMasqueProxy) handleReq(c net.Conn) {
	br := bufio.NewReader(c)
	req, err := http.ReadRequest(br)
	if err != nil {
		zap.S().Debug("Failed to read HTTP request", "err", err)
		return
	}
	// logger = logger.With("conn", c, "req", req)

	// output request for debugging
	// logger.Debug("handling request")

	var wg sync.WaitGroup

	if req.Method == http.MethodConnect {
		response := &http.Response{
			StatusCode: 200,
			ProtoMajor: 1,
			ProtoMinor: 1,
		}
		err := response.Write(c)
		if err != nil {
			zap.S().Error("Error calling response.Write", "err", err)
		}

		if masqueConn := p.handleConnectMasque(c, req); masqueConn != nil {
			defer func() {
				if err := c.Close(); err != nil {
					zap.S().Error("Error closing c", "err", err)
				}
			}()
			defer func() {
				if err := masqueConn.Close(); err != nil {
					zap.S().Error("Error closing masqueConn", "err", err)
				}
			}()
			wg.Add(1)
			go transfer(masqueConn, c, &wg)
			wg.Add(1)
			go transfer(c, masqueConn, &wg)
			wg.Wait()
		}
	} else {
		// Non-CONNECT requests need to be passed through as is, without the Proxy-Authorization header.
		req.Header.Del("Proxy-Authorization")

		// If req doesn't specify a port number for the host and is http, add port 80.
		if req.URL.Scheme == "http" && !strings.Contains(req.URL.Host, ":") {
			req.URL.Host = req.URL.Host + ":80"
		}

		if masqueConn := p.handleConnectMasque(c, req); masqueConn != nil {
			defer func() {
				if err := c.Close(); err != nil {
					zap.S().Error("Error closing c", "err", err)
				}
			}()
			defer func() {
				if err := masqueConn.Close(); err != nil {
					zap.S().Error("Error closing masqueConn", "err", err)
				}
			}()
			// Replay the request to the masque connection.
			err := req.Write(masqueConn)
			if err != nil {
				zap.S().Error("Error calling req.Write", "err", err)
			}
			wg.Add(1)
			go transfer(masqueConn, c, &wg)
			wg.Add(1)
			go transfer(c, masqueConn, &wg)
			wg.Wait()
		}
	}
}

func (p *EnvoyMasqueProxy) connectToRelay(certData []byte) (*masqueH2.Client, error) {
	// this thing really wants an slog instance 🤷
	// maybe there's a way to bridge to zap? maybe I should put slog back
	logger := slog.New(slog.NewTextHandler(os.Stdout, &slog.HandlerOptions{
		Level: slog.LevelDebug,
	}))

	config := masqueH2.ClientConfig{
		ProxyAddr:  fmt.Sprintf("%v:%v", p.UpstreamServer, p.UpstreamPort),
		AuthToken:  p.token,
		CertData:   certData,
		IgnoreCert: p.insecure,
		Logger:     logger,
	}

	c := masqueH2.NewClient(config)

	err := c.ConnectToProxy()
	if err != nil {
		return nil, err
	}
	return c, nil
}
