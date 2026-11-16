//go:build windows

package main

import (
	"bytes"
	"context"
	"io"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"syscall"
	"time"
	"unsafe"
)

var pCloseHandle = kernel32.NewProc("CloseHandle")

// updateLoop : vérifie les nouvelles versions au démarrage puis toutes les 6 h,
// et s'installe seule quand aucun appareil ne lit de vidéo du PC (les téléchargements reprennent ensuite).
func updateLoop(ctx context.Context, a *App, self string) {
	if !strings.EqualFold(filepath.Dir(self), installDir()) {
		return // lancé hors du dossier d'installation (essai) : pas de remplacement
	}
	wait := 2 * time.Minute
	for {
		select {
		case <-ctx.Done():
			return
		case <-time.After(wait):
		}
		wait = 6 * time.Hour
		ver, url, size, err := checkUpdate(ctx, latestURL)
		if err != nil || ver == "" {
			continue
		}
		for !a.idleForUpdate() { // une vidéo est lue depuis le PC : plus tard
			select {
			case <-ctx.Done():
				return
			case <-time.After(5 * time.Minute):
			}
		}
		if err := replaceSelf(ctx, self, url, size); err != nil {
			wait = time.Hour
			continue
		}
		exec.Command(self, "--sur-place", "--maj").Start()
		a.onQuit()
		return
	}
}

// replaceSelf : télécharge le nouvel exe à côté, vérifie qu'il est complet, puis prend sa place
// (Windows autorise à renommer un exe en cours d'exécution ; l'ancien est effacé au prochain démarrage).
func replaceSelf(ctx context.Context, self, url string, size int64) error {
	req, _ := http.NewRequestWithContext(ctx, "GET", url, nil)
	req.Header.Set("User-Agent", "KoalaTV-Relais/"+version)
	r, err := (&http.Client{Timeout: 10 * time.Minute}).Do(req)
	if err != nil {
		return err
	}
	defer r.Body.Close()
	if r.StatusCode != 200 {
		return os.ErrInvalid
	}
	b, err := io.ReadAll(io.LimitReader(r.Body, 200<<20))
	if err != nil {
		return err
	}
	if (size > 0 && int64(len(b)) != size) || len(b) < 1<<20 || !bytes.HasPrefix(b, []byte("MZ")) {
		return os.ErrInvalid
	}
	upd := self + ".update"
	if err := os.WriteFile(upd, b, 0o755); err != nil {
		return err
	}
	old := self + ".old"
	os.Remove(old)
	if err := os.Rename(self, old); err != nil {
		os.Remove(upd)
		return err
	}
	if err := os.Rename(upd, self); err != nil {
		os.Rename(old, self)
		return err
	}
	return nil
}

// waitMutex : après une mise à jour, l'ancienne version s'arrête juste après avoir lancé la nouvelle.
func waitMutex(name string, d time.Duration) bool {
	end := time.Now().Add(d)
	for {
		h, _, e := pCreateMutex.Call(0, 0, uintptr(unsafe.Pointer(u16(name))))
		if e != syscall.Errno(183) {
			return true
		}
		pCloseHandle.Call(h)
		if time.Now().After(end) {
			return false
		}
		time.Sleep(500 * time.Millisecond)
	}
}
