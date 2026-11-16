package main

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"
)

// ---- faux Firebase : arbre JSON, PUT/PATCH/DELETE/GET, flux d'évènements ----

type fakeFB struct {
	mu   sync.Mutex
	root map[string]any
	subs []chan struct{}
}

func (f *fakeFB) node(path string, create bool) (map[string]any, string) {
	parts := strings.Split(strings.Trim(path, "/"), "/")
	cur := f.root
	for _, p := range parts[:len(parts)-1] {
		nx, ok := cur[p].(map[string]any)
		if !ok {
			if !create {
				return nil, ""
			}
			nx = map[string]any{}
			cur[p] = nx
		}
		cur = nx
	}
	return cur, parts[len(parts)-1]
}

func (f *fakeFB) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	path := strings.TrimSuffix(r.URL.Path, ".json")
	if r.Header.Get("Accept") == "text/event-stream" {
		ch := make(chan struct{}, 8)
		f.mu.Lock()
		f.subs = append(f.subs, ch)
		f.mu.Unlock()
		w.Header().Set("Content-Type", "text/event-stream")
		fmt.Fprint(w, "event: put\ndata: {}\n\n")
		w.(http.Flusher).Flush()
		for {
			select {
			case <-r.Context().Done():
				return
			case <-ch:
				fmt.Fprint(w, "event: put\ndata: {}\n\n")
				w.(http.Flusher).Flush()
			}
		}
	}
	f.mu.Lock()
	defer f.mu.Unlock()
	parent, key := f.node(path, r.Method != "GET")
	switch r.Method {
	case "GET":
		var v any
		if parent != nil {
			v = parent[key]
		}
		json.NewEncoder(w).Encode(v)
		return
	case "DELETE":
		delete(parent, key)
	case "PUT":
		var v any
		json.NewDecoder(r.Body).Decode(&v)
		parent[key] = v
	case "PATCH":
		var v map[string]any
		json.NewDecoder(r.Body).Decode(&v)
		m, _ := parent[key].(map[string]any)
		if m == nil {
			m = map[string]any{}
		}
		for k, x := range v {
			m[k] = x
		}
		parent[key] = m
	}
	io.WriteString(w, "{}")
	for _, s := range f.subs {
		select {
		case s <- struct{}{}:
		default:
		}
	}
}

func (f *fakeFB) get(path string) any {
	f.mu.Lock()
	defer f.mu.Unlock()
	p, k := f.node(path, false)
	if p == nil {
		return nil
	}
	return p[k]
}

func (f *fakeFB) put(path string, v any) {
	b, _ := json.Marshal(v)
	req, _ := http.NewRequest("PUT", "/"+path+".json", bytes.NewReader(b))
	f.ServeHTTP(httptest.NewRecorder(), req)
}

// ---- faux serveur Xtream : redirection, plages d'octets, débit réglable ----

func fakeXtream(t *testing.T, data []byte, bytesPerSec int) *httptest.Server {
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.HasPrefix(r.URL.Path, "/series/") || strings.HasPrefix(r.URL.Path, "/movie/") {
			if !strings.Contains(r.URL.Path, "/thomas/secret/") {
				http.Error(w, "identifiants", 401)
				return
			}
			http.Redirect(w, r, "/real"+r.URL.Path, 302)
			return
		}
		var a, b int
		fmt.Sscanf(r.Header.Get("Range"), "bytes=%d-%d", &a, &b)
		if b >= len(data) {
			b = len(data) - 1
		}
		w.Header().Set("Content-Range", fmt.Sprintf("bytes %d-%d/%d", a, b, len(data)))
		w.Header().Set("Content-Length", strconv.Itoa(b-a+1))
		w.WriteHeader(206)
		chunk := 64 << 10
		for p := a; p <= b; p += chunk {
			e := p + chunk
			if e > b+1 {
				e = b + 1
			}
			if _, err := w.Write(data[p:e]); err != nil {
				return
			}
			if bytesPerSec > 0 {
				time.Sleep(time.Duration(int64(e-p) * int64(time.Second) / int64(bytesPerSec)))
			}
		}
	}))
}

func waitFor(t *testing.T, what string, d time.Duration, ok func() bool) {
	t.Helper()
	end := time.Now().Add(d)
	for time.Now().Before(end) {
		if ok() {
			return
		}
		time.Sleep(50 * time.Millisecond)
	}
	t.Fatalf("délai dépassé : %s", what)
}

// Vecteur calculé indépendamment (Python) avec l'algorithme des applis.
func TestPairingCompatible(t *testing.T) {
	if got := pairURL("ABCD2345"); !strings.Contains(got, "/h/p4d9896500b5e0f99c96cb2fe9f1c8d33ca225f00/x.json") {
		t.Fatal(got)
	}
	if h := accountHash("http://srv.example:8080", "thomas", "secret"); h != "206ec5808d3be899eb22e862d1f9d0f4969a1b3c" {
		t.Fatal(h)
	}
}

func TestEndToEnd(t *testing.T) {
	data := make([]byte, 9_000_000)
	for i := range data {
		data[i] = byte(i*31 + i/7)
	}
	fb := &fakeFB{root: map[string]any{}}
	fbSrv := httptest.NewServer(fb)
	defer fbSrv.Close()
	xt := fakeXtream(t, data, 6_000_000)
	defer xt.Close()
	dbURL = fbSrv.URL

	tmp := t.TempDir()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	a := newApp(ctx, filepath.Join(tmp, "conf"))
	a.cfg.Dir = filepath.Join(tmp, "Vidéos")
	a.cfg.Port = 0
	if err := a.Start(); err != nil {
		t.Fatal(err)
	}

	// 1. liaison : un appareil envoie les identifiants au code affiché (chiffrés comme le font les applis)
	waitFor(t, "code de liaison", 3*time.Second, func() bool { a.mu.Lock(); defer a.mu.Unlock(); return a.pairCode != "" })
	a.mu.Lock()
	code := a.pairCode
	a.mu.Unlock()
	plain, _ := json.Marshal(Creds{Server: xt.URL, User: "thomas", Pass: "secret"})
	fb.put(strings.TrimPrefix(strings.TrimSuffix(strings.TrimPrefix(pairURL(code), dbURL), ".json"), "/"),
		map[string]any{"d": fmt.Sprintf("%x", pairXor(code, plain)), "at": 1})
	waitFor(t, "liaison", 6*time.Second, a.linked)
	acc := accountHash(xt.URL, "thomas", "secret")
	waitFor(t, "adresse publiée", 8*time.Second, func() bool { return fb.get("h/"+acc+"/r") != nil })
	r := fb.get("h/" + acc + "/r").(map[string]any)
	t.Logf("adresse publiée : port %v, jeton %v", r["port"], r["tok"] != "")

	// 2. demande de téléchargement venue d'une appli
	fb.put("h/"+acc+"/q/e101", map[string]any{"kind": "series", "id": "101", "ext": "mkv", "t": "Série · S1 É1", "sid": "9", "sn": "Série : l'essai"})
	waitFor(t, "prise en compte", 5*time.Second, func() bool { return a.lib.Get("e101") != nil })
	if fb.get("h/"+acc+"/q/e101") != nil {
		t.Fatal("la demande doit être retirée de la file")
	}

	// 3. lecture pendant le téléchargement, par le serveur du PC, avec une plage d'octets
	base := fmt.Sprintf("http://127.0.0.1:%d/v/e101.mkv?t=%s", a.cfg.Port, a.cfg.Token)
	req, _ := http.NewRequest("GET", base, nil)
	req.Header.Set("Range", "bytes=1000000-")
	t0 := time.Now()
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	got, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	t.Logf("lecture pendant le téléchargement : %d %s, %d octets en %v", resp.StatusCode, resp.Header.Get("Content-Range"), len(got), time.Since(t0).Round(time.Millisecond))
	if resp.StatusCode != 206 || !bytes.Equal(got, data[1000000:]) {
		t.Fatal("contenu lu différent")
	}
	waitFor(t, "téléchargement fini", 10*time.Second, func() bool { return a.lib.Get("e101").State == stDone })
	disk, _ := os.ReadFile(a.lib.Get("e101").Path)
	if !bytes.Equal(disk, data) {
		t.Fatal("fichier différent")
	}
	t.Logf("fichier : %s", strings.TrimPrefix(a.lib.Get("e101").Path, tmp))
	waitFor(t, "bibliothèque publiée", 8*time.Second, func() bool {
		rl, _ := fb.get("h/" + acc + "/rl/e101").(map[string]any)
		return rl != nil && rl["st"] == stDone
	})

	// 4. jeton exigé
	if r, _ := http.Get(fmt.Sprintf("http://127.0.0.1:%d/v/e101.mkv?t=faux", a.cfg.Port)); r.StatusCode != 403 {
		t.Fatal("jeton non vérifié")
	}

	// 5. quelqu'un regarde en ligne : le PC se met en pause ; lecture locale (l=1) : il continue
	fb.put("h/"+acc+"/q/m202", map[string]any{"kind": "movie", "id": "202", "ext": "mp4", "t": "Film"})
	waitFor(t, "film en cours", 5*time.Second, func() bool { it := a.lib.Get("m202"); return it != nil && it.Done > 0 })
	fb.put("h/"+acc+"/p/anTV", map[string]any{"n": "LG OLED", "t": "Un film", "at": time.Now().UnixMilli()})
	waitFor(t, "pause", 5*time.Second, func() bool { return a.lib.PauseWhy() != "" })
	d1 := a.lib.Get("m202").Done
	time.Sleep(700 * time.Millisecond)
	if d2 := a.lib.Get("m202").Done; d2 != d1 {
		t.Fatalf("le téléchargement continue pendant la pause (%d → %d)", d1, d2)
	}
	t.Logf("pause : %s", a.lib.PauseWhy())
	fb.put("h/"+acc+"/p/anTV", map[string]any{"n": "LG OLED", "t": "Un film (depuis le PC)", "at": time.Now().UnixMilli(), "l": 1})
	waitFor(t, "reprise", 5*time.Second, func() bool { return a.lib.PauseWhy() == "" })
	waitFor(t, "film fini", 10*time.Second, func() bool { return a.lib.Get("m202").State == stDone })

	// 6. retrait demandé par une appli : fichier effacé
	path := a.lib.Get("m202").Path
	fb.put("h/"+acc+"/q/m202", map[string]any{"del": 1})
	waitFor(t, "retrait", 5*time.Second, func() bool { _, err := os.Stat(path); return a.lib.Get("m202") == nil && os.IsNotExist(err) })

	// 7. page de réglages : refusée depuis le réseau, accessible depuis le PC
	st, _ := http.Get(fmt.Sprintf("http://127.0.0.1:%d/api/state", a.cfg.Port))
	var s map[string]any
	json.NewDecoder(st.Body).Decode(&s)
	t.Logf("page : lié=%v, éléments=%d", s["linked"], len(s["items"].([]any)))
}

// Serveur qui envoie le début de chaque requête vite (8 Mo), puis bride à 300 ko/s, comme celui de l'abonnement.
func throttled(size int, gap time.Duration, boost bool) (time.Duration, int, error) {
	data := make([]byte, size)
	for i := range data {
		data[i] = byte(i*13 + i/5)
	}
	reqs := 0
	var mu sync.Mutex
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		reqs++
		mu.Unlock()
		var a, b int
		fmt.Sscanf(r.Header.Get("Range"), "bytes=%d-%d", &a, &b)
		if b >= len(data) {
			b = len(data) - 1
		}
		w.Header().Set("Content-Range", fmt.Sprintf("bytes %d-%d/%d", a, b, len(data)))
		w.Header().Set("Content-Length", strconv.Itoa(b-a+1))
		w.WriteHeader(206)
		sent := 0
		for p := a; p <= b; p += 64 << 10 {
			e := p + 64<<10
			if e > b+1 {
				e = b + 1
			}
			if _, err := w.Write(data[p:e]); err != nil {
				return
			}
			sent += e - p
			if sent > 8<<20 {
				time.Sleep(time.Duration(int64(e-p) * int64(time.Second) / 300_000))
			}
		}
	}))
	defer srv.Close()
	tmp, _ := os.MkdirTemp("", "ktv")
	defer os.RemoveAll(tmp)
	l := newLib(filepath.Join(tmp, "lib.json"))
	l.urlFor = func(*Item) string { return srv.URL + "/v" }
	l.free = func(string) int64 { return 0 }
	l.gap = gap
	l.boost = func() bool { return boost }
	it := &Item{Key: "e1", Path: filepath.Join(tmp, "e1.mkv")}
	l.items[it.Key] = it
	t0 := time.Now()
	l.download(context.Background(), it)
	d := time.Since(t0)
	got, _ := os.ReadFile(it.Path)
	if it.State != stDone || !bytes.Equal(got, data) {
		return d, reqs, fmt.Errorf("état %v, %d octets, identiques %v", it.State, len(got), bytes.Equal(got, data))
	}
	return d, reqs, nil
}

func TestReopenWhenThrottled(t *testing.T) {
	d, n, err := throttled(40<<20, 0, true)
	t.Logf("sans délai : 40 Mo en %v, %d requêtes (sans relance : plus de 100 s)", d.Round(time.Millisecond), n)
	if err != nil || d > 40*time.Second {
		t.Fatal("relances inefficaces", err)
	}
	// 3 requêtes sans relance : la taille, la fin du fichier (lue d'abord), puis le reste
	// discret : pas plus d'une relance par intervalle
	d, n, err = throttled(40<<20, 5*time.Second, true)
	t.Logf("une relance toutes les 5 s : %v, %d requêtes", d.Round(time.Millisecond), n)
	if err != nil || n > 3+int(d/(5*time.Second))+1 {
		t.Fatal("trop de relances", err)
	}
	// désactivé : une seule requête (3 Mo après le départ rapide, ~10 s)
	d, n, err = throttled(11<<20, 0, false)
	t.Logf("désactivé : %v, %d requêtes", d.Round(time.Millisecond), n)
	if err != nil || n != 3 {
		t.Fatal("relance alors que c'est désactivé", err)
	}
}
