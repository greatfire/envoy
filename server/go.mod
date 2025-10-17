module github.com/greatfire/envoy/server

go 1.24.0

toolchain go1.24.7

require (
	github.com/francoismichel/http-signature-auth-go v0.0.0-20240702170343-6a6cf8ee5321
	github.com/goccy/go-yaml v1.18.0
	github.com/invisv-privacy/masque v0.0.0-20240807000525-d8d7169c2ca2
	golang.org/x/crypto v0.42.0
)

require (
	github.com/mattn/go-colorable v0.1.13 // indirect
	github.com/mattn/go-isatty v0.0.19 // indirect
	github.com/quic-go/quic-go v0.42.0 // indirect
	github.com/rs/zerolog v1.31.0 // indirect
	go4.org/intern v0.0.0-20230525184215-6c62f75575cb // indirect
	go4.org/unsafe/assume-no-moving-gc v0.0.0-20231121144256-b99613f794b6 // indirect
	golang.org/x/exp v0.0.0-20230713183714-613f0c0eb8a1 // indirect
	golang.org/x/net v0.43.0 // indirect
	golang.org/x/sys v0.36.0 // indirect
	golang.org/x/text v0.29.0 // indirect
	inet.af/netaddr v0.0.0-20230525184311-b8eac61e914a // indirect
)

replace github.com/francoismichel/http-signature-auth-go => ./http-signature-auth-go
