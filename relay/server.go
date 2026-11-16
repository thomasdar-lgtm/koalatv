package main

// Serveur du PC :
//  - /v/<clé>?t=<jeton> : les vidéos, pour les appareils du réseau local (lecture pendant le téléchargement possible) ;
//  - / et /api/… : la page de réglages, accessible seulement depuis le PC lui-même.

import (
	"embed"
	"encoding/json"
	"fmt"
	"io"
	"mime"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

//go:embed ui.html icon.png
var uiFiles embed.FS

func local(r *http.Request) bool {
	host, _, _ := net.SplitHostPort(r.RemoteAddr)
	ip := net.ParseIP(host)
	return ip != nil && ip.IsLoopback()
}

func (a *App) routes() http.Handler {
	m := http.NewServeMux()
	m.HandleFunc("/ping", func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Query().Get("t") != a.cfg.Token {
			http.Error(w, "refusé", 403)
			return
		}
		writeJSON(w, map[string]any{"ok": 1, "n": a.cfg.Name, "v": version})
	})
	m.HandleFunc("/v/", a.serveVideo)
	m.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		if !local(r) {
			http.Error(w, "refusé", 403)
			return
		}
		name := strings.TrimPrefix(r.URL.Path, "/")
		if name == "" {
			name = "ui.html"
		}
		b, err := uiFiles.ReadFile(name)
		if err != nil {
			http.NotFound(w, r)
			return
		}
		w.Header().Set("Content-Type", mime.TypeByExtension(filepath.Ext(name)))
		w.Header().Set("Cache-Control", "no-store")
		w.Write(b)
	})
	m.HandleFunc("/api/", func(w http.ResponseWriter, r *http.Request) {
		if !local(r) {
			http.Error(w, "refusé", 403)
			return
		}
		a.api(w, r)
	})
	return m
}

func writeJSON(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	json.NewEncoder(w).Encode(v)
}

// serveVideo envoie la vidéo, avec les plages d'octets (avance, retour) et l'attente des octets pas encore téléchargés.
func (a *App) serveVideo(w http.ResponseWriter, r *http.Request) {
	if r.URL.Query().Get("t") != a.cfg.Token {
		http.Error(w, "refusé", 403)
		return
	}
	key := strings.TrimSuffix(strings.TrimPrefix(r.URL.Path, "/v/"), filepath.Ext(r.URL.Path))
	it := a.lib.Get(key)
	if it == nil {
		http.NotFound(w, r)
		return
	}
	// taille connue dès que le téléchargement a commencé
	deadline := time.Now().Add(60 * time.Second)
	for {
		_, total, _, ok := a.lib.Avail(key, 0)
		if !ok {
			http.NotFound(w, r)
			return
		}
		if total > 0 {
			break
		}
		if time.Now().After(deadline) || r.Context().Err() != nil {
			http.Error(w, "pas encore commencé", 503)
			return
		}
		time.Sleep(300 * time.Millisecond)
	}
	_, total, _, _ := a.lib.Avail(key, 0)
	start, end := int64(0), total-1
	partial := false
	if rg := r.Header.Get("Range"); strings.HasPrefix(rg, "bytes=") {
		p := strings.SplitN(strings.TrimPrefix(rg, "bytes="), "-", 2)
		if p[0] == "" { // derniers octets
			n, _ := strconv.ParseInt(p[1], 10, 64)
			start = total - n
		} else {
			start, _ = strconv.ParseInt(p[0], 10, 64)
			if len(p) > 1 && p[1] != "" {
				end, _ = strconv.ParseInt(p[1], 10, 64)
			}
		}
		if start < 0 {
			start = 0
		}
		if end >= total {
			end = total - 1
		}
		if start >= total || start > end {
			w.Header().Set("Content-Range", fmt.Sprintf("bytes */%d", total))
			http.Error(w, "plage invalide", 416)
			return
		}
		partial = true
	}
	f, err := os.Open(it.Path)
	if err != nil {
		http.Error(w, "fichier introuvable", 404)
		return
	}
	defer f.Close()
	ctype := mime.TypeByExtension(filepath.Ext(it.Path))
	switch strings.ToLower(filepath.Ext(it.Path)) {
	case ".mkv":
		ctype = "video/x-matroska"
	case ".mp4", ".m4v":
		ctype = "video/mp4"
	case ".avi":
		ctype = "video/x-msvideo"
	}
	if ctype == "" {
		ctype = "application/octet-stream"
	}
	w.Header().Set("Content-Type", ctype)
	w.Header().Set("Accept-Ranges", "bytes")
	w.Header().Set("Content-Length", strconv.FormatInt(end-start+1, 10))
	w.Header().Set("Access-Control-Allow-Origin", "*")
	if partial {
		w.Header().Set("Content-Range", fmt.Sprintf("bytes %d-%d/%d", start, end, total))
		w.WriteHeader(206)
	} else {
		w.WriteHeader(200)
	}
	if r.Method == "HEAD" {
		return
	}
	a.reading(+1)
	defer a.reading(-1)
	buf := make([]byte, 256<<10)
	pos := end + 1
	pos = start
	waited := time.Duration(0)
	for pos <= end {
		avail, _, state, ok := a.lib.Avail(key, pos)
		if !ok {
			return
		}
		if avail <= 0 {
			// pas encore téléchargé : on attend (abandon après 2 min sans progrès)
			if state == stFailed || waited > 2*time.Minute || r.Context().Err() != nil {
				return
			}
			time.Sleep(200 * time.Millisecond)
			if state != stRunning {
				waited += 200 * time.Millisecond
			}
			continue
		}
		waited = 0
		n := min64(min64(avail, int64(len(buf))), end-pos+1)
		got, err := f.ReadAt(buf[:n], pos)
		if got > 0 {
			if _, werr := w.Write(buf[:got]); werr != nil {
				return
			}
			pos += int64(got)
		}
		if err != nil && err != io.EOF {
			return
		}
	}
}

func (a *App) api(w http.ResponseWriter, r *http.Request) {
	var in map[string]string
	if r.Method == "POST" {
		json.NewDecoder(r.Body).Decode(&in)
	}
	switch strings.TrimPrefix(r.URL.Path, "/api/") {
	case "state":
		writeJSON(w, a.state())
	case "drives":
		writeJSON(w, drives())
	case "dir":
		dir := strings.TrimSpace(in["dir"])
		if err := os.MkdirAll(dir, 0o755); err != nil {
			writeJSON(w, map[string]any{"err": "Dossier impossible à créer : " + err.Error()})
			return
		}
		test := filepath.Join(dir, ".koalatv-test")
		if err := os.WriteFile(test, []byte("ok"), 0o644); err != nil {
			writeJSON(w, map[string]any{"err": "Écriture impossible dans ce dossier"})
			return
		}
		os.Remove(test)
		a.mu.Lock()
		a.cfg.Dir = dir
		a.mu.Unlock()
		a.saveConfig()
		a.publish()
		writeJSON(w, map[string]any{"ok": 1})
	case "remove":
		a.lib.Remove(in["k"])
		writeJSON(w, map[string]any{"ok": 1})
	case "retry":
		a.lib.Retry(in["k"])
		writeJSON(w, map[string]any{"ok": 1})
	case "pause":
		a.mu.Lock()
		a.manualPause = in["on"] == "1"
		a.mu.Unlock()
		a.updatePause()
		writeJSON(w, map[string]any{"ok": 1})
	case "autostart":
		err := setAutostart(in["on"] == "1")
		writeJSON(w, map[string]any{"ok": err == nil})
	case "unlink":
		a.unlink()
		writeJSON(w, map[string]any{"ok": 1})
	case "quit":
		writeJSON(w, map[string]any{"ok": 1})
		if a.onQuit != nil {
			go func() { time.Sleep(300 * time.Millisecond); a.onQuit() }()
		}
	case "open":
		openPath(a.cfg.Dir)
		writeJSON(w, map[string]any{"ok": 1})
	default:
		http.NotFound(w, r)
	}
}
