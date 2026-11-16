//go:build !windows

package main

import (
	"flag"
	"log"
	"os"
	"os/signal"
)

func main() {
	data := flag.String("data", dataDirDefault(), "dossier des réglages")
	db := flag.String("db", "", "base (essais)")
	ip := flag.String("ip", "", "adresse annoncée (essais)")
	flag.Parse()
	if *ip != "" {
		forcedIPs = []string{*ip}
	}
	if *db != "" {
		dbURL = *db
	}
	a, cancel, err := startApp(*data)
	if err != nil {
		log.Fatal(err)
	}
	log.Println("KoalaTV Relais", version, "sur", a.uiURL())
	c := make(chan os.Signal, 1)
	signal.Notify(c, os.Interrupt)
	<-c
	cancel()
}
