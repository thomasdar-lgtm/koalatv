package main

// Accès à la base Firebase partagée par tous les appareils KoalaTV du compte (API REST).

import (
	"bufio"
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"
)

var dbURL = "https://koalatv-dae7b-default-rtdb.europe-west1.firebasedatabase.app"

var httpc = &http.Client{Timeout: 20 * time.Second}

func sha(s string) []byte { h := sha256.Sum256([]byte(s)); return h[:] }

// Empreinte du compte : même calcul que les applis (« koalatv|serveur|utilisateur|mot de passe »).
func accountHash(server, user, pass string) string {
	return hex.EncodeToString(sha("koalatv|" + strings.ToLower(server) + "|" + user + "|" + pass))[:40]
}

type FB struct{ base string } // …/h/<compte>

func newFB(acc string) *FB { return &FB{strings.TrimRight(dbURL, "/") + "/h/" + acc} }

func (f *FB) url(path string) string { return f.base + "/" + path + ".json" }

func fbDo(method, url string, body any) ([]byte, error) {
	var rd io.Reader
	if body != nil {
		b, err := json.Marshal(body)
		if err != nil {
			return nil, err
		}
		rd = bytes.NewReader(b)
	}
	req, err := http.NewRequest(method, url, rd)
	if err != nil {
		return nil, err
	}
	if rd != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	r, err := httpc.Do(req)
	if err != nil {
		return nil, err
	}
	defer r.Body.Close()
	out, _ := io.ReadAll(r.Body)
	if r.StatusCode >= 300 {
		return nil, fmt.Errorf("base : %d", r.StatusCode)
	}
	return out, nil
}

func (f *FB) Get(path string, v any) error {
	b, err := fbDo("GET", f.url(path), nil)
	if err != nil {
		return err
	}
	return json.Unmarshal(b, v)
}
func (f *FB) Put(path string, v any) error   { _, err := fbDo("PUT", f.url(path), v); return err }
func (f *FB) Patch(path string, v any) error { _, err := fbDo("PATCH", f.url(path), v); return err }
func (f *FB) Delete(path string) error       { _, err := fbDo("DELETE", f.url(path), nil); return err }

// Listen suit un chemin en direct (flux d'évènements de Firebase) : onChange est appelé à chaque modification.
// Rend la main quand le contexte est annulé ; se reconnecte seul après une coupure.
func (f *FB) Listen(ctx context.Context, path string, onChange func()) {
	for ctx.Err() == nil {
		req, _ := http.NewRequestWithContext(ctx, "GET", f.url(path), nil)
		req.Header.Set("Accept", "text/event-stream")
		r, err := (&http.Client{}).Do(req)
		if err == nil {
			sc := bufio.NewScanner(r.Body)
			sc.Buffer(make([]byte, 64*1024), 4*1024*1024)
			for sc.Scan() {
				line := sc.Text()
				if strings.HasPrefix(line, "event: put") || strings.HasPrefix(line, "event: patch") {
					onChange()
				}
			}
			r.Body.Close()
		}
		select {
		case <-ctx.Done():
			return
		case <-time.After(5 * time.Second):
		}
	}
}
