package main

// Mise à jour automatique du Relais : la release GitHub la plus récente porte « Relais x.y.z »
// dans sa description et le fichier KoalaTV-Relais.exe.

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"regexp"
	"strconv"
	"strings"
	"sync/atomic"
	"time"
)

const latestURL = "https://api.github.com/repos/thomasdar-lgtm/koalatv/releases/latest"

var relayVerRe = regexp.MustCompile(`Relais (\d+(?:\.\d+)+)`)

type release struct {
	Body   string `json:"body"`
	Assets []struct {
		Name string `json:"name"`
		URL  string `json:"browser_download_url"`
		Size int64  `json:"size"`
	} `json:"assets"`
}

// newer : a est-elle plus récente que b (« 1.0.10 » > « 1.0.9 ») ?
func newer(a, b string) bool {
	pa, pb := strings.Split(a, "."), strings.Split(b, ".")
	for i := 0; i < len(pa) || i < len(pb); i++ {
		var x, y int
		if i < len(pa) {
			x, _ = strconv.Atoi(pa[i])
		}
		if i < len(pb) {
			y, _ = strconv.Atoi(pb[i])
		}
		if x != y {
			return x > y
		}
	}
	return false
}

// checkUpdate : version et adresse du nouvel exe, ou "" si le Relais est à jour.
func checkUpdate(ctx context.Context, url string) (ver, exe string, size int64, err error) {
	req, _ := http.NewRequestWithContext(ctx, "GET", url, nil)
	req.Header.Set("Accept", "application/vnd.github+json")
	req.Header.Set("User-Agent", "KoalaTV-Relais/"+version)
	r, err := (&http.Client{Timeout: 30 * time.Second}).Do(req)
	if err != nil {
		return "", "", 0, err
	}
	defer r.Body.Close()
	if r.StatusCode != 200 {
		return "", "", 0, errors.New(r.Status)
	}
	var rel release
	if err := json.NewDecoder(r.Body).Decode(&rel); err != nil {
		return "", "", 0, err
	}
	m := relayVerRe.FindStringSubmatch(rel.Body)
	if m == nil || !newer(m[1], version) {
		return "", "", 0, nil
	}
	for _, a := range rel.Assets {
		if a.Name == "KoalaTV-Relais.exe" {
			return m[1], a.URL, a.Size, nil
		}
	}
	return "", "", 0, nil
}

// idleForUpdate : aucun appareil ne lit une vidéo du PC (rien depuis 10 min).
func (a *App) idleForUpdate() bool {
	return atomic.LoadInt32(&a.serving) == 0 && time.Now().UnixMilli()-atomic.LoadInt64(&a.lastServe) > 10*60_000
}
