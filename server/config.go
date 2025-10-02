package main

import (
	"os"
	yaml "github.com/goccy/go-yaml"
)

type User struct {
	Name string
	Key string
}

type AllowedHosts struct {
	Match []string       `yaml:"match"`
	EndsWith []string `yaml:"ends-with"`
}

type Config struct {
	Listen string                 `yaml:"listen"`
	Users []User                  `yaml:"users"`
	AllowedHosts AllowedHosts `yaml:"allowed-hosts"`
}


func getConfig(path string) (Config, error) {
	var config Config

	content, err := os.ReadFile(path)
	if err != nil {
		return config, err
	}

	err = yaml.Unmarshal(content, &config)
	if err != nil {
		return config, err
	}

	return config, nil
}
