package main

import (
	"io"
	"log"
	"net/http"
	"strings"
)

// borrowed from github.com/elazarl/goproxy
type flushWriter struct {
	w io.Writer
}

func (fw flushWriter) Write(p []byte) (int, error) {
	n, err := fw.w.Write(p)
	if f, ok := fw.w.(http.Flusher); ok {
		// only flush if the Writer implements the Flusher interface.
		f.Flush()
	}

	return n, err
}

func copyResponse(w http.ResponseWriter, resp *http.Response) (error) {
	for k, vs := range resp.Header {
		// direct assignment to avoid canonicalization
		w.Header()[k] = append([]string(nil), vs...)
	}
	w.WriteHeader(resp.StatusCode)

	// borrowed from github.com/elazarl/goproxy
	var copyWriter io.Writer = w
	// Content-Type header may also contain charset definition, so here we need to check the prefix.
	// Transfer-Encoding can be a list of comma separated values, so we use Contains() for it.
	if strings.HasPrefix(w.Header().Get("content-type"), "text/event-stream") ||
		strings.Contains(w.Header().Get("transfer-encoding"), "chunked") {
		// server-side events, flush the buffered data to the client.
		copyWriter = &flushWriter{w: w}
	}

	nr, err := io.Copy(copyWriter, resp.Body)
	if err := resp.Body.Close(); err != nil {
		log.Printf("Can't close response body %v", err)
		return err
	}
	log.Printf("Copied %v bytes to client error=%v", nr, err)
	return nil
}