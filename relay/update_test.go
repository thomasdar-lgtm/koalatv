package main

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestUpdateCheck(t *testing.T) {
	if !newer("1.0.10", "1.0.9") || newer("1.0.3", "1.0.3") || newer("1.0.2", "1.0.3") || !newer("1.1", "1.0.9") {
		t.Fatal("comparaison des versions")
	}
	body := `{"body":"Relais 9.9.9","assets":[{"name":"KoalaTV.apk","browser_download_url":"x"},{"name":"KoalaTV-Relais.exe","browser_download_url":"https://e/x.exe","size":123}]}`
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.Write([]byte(body)) }))
	defer srv.Close()
	v, u, n, err := checkUpdate(context.Background(), srv.URL)
	if err != nil || v != "9.9.9" || u != "https://e/x.exe" || n != 123 {
		t.Fatal(v, u, n, err)
	}
	body = `{"body":"Relais ` + version + `","assets":[{"name":"KoalaTV-Relais.exe","browser_download_url":"y"}]}`
	if v, _, _, _ := checkUpdate(context.Background(), srv.URL); v != "" {
		t.Fatal("même version : pas de mise à jour")
	}
	body = `{"body":"","assets":[]}`
	if v, _, _, _ := checkUpdate(context.Background(), srv.URL); v != "" {
		t.Fatal("description sans version")
	}
}
