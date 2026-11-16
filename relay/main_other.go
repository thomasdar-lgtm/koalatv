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
	flag.Parse()
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
