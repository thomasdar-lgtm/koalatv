package main

// KoalaTV Relais : le PC télécharge films et épisodes pour les appareils KoalaTV de la maison,
// qui les lisent ensuite par le réseau local.

import (
	"context"
	"fmt"
	"strings"
	"time"
)

// status : résumé court (infobulle de l'icône).
func (a *App) status() string {
	if !a.linked() {
		return "KoalaTV Relais · à relier à ton compte"
	}
	if w := a.lib.PauseWhy(); w != "" {
		return "KoalaTV Relais · " + w
	}
	for _, it := range a.lib.Snapshot() {
		if it.State == stRunning {
			return fmt.Sprintf("KoalaTV Relais · %s · %d %%", trim(it.Title, 60), it.Percent())
		}
	}
	return "KoalaTV Relais · prêt"
}

func trim(s string, n int) string {
	r := []rune(s)
	if len(r) <= n {
		return s
	}
	return strings.TrimSpace(string(r[:n-1])) + "…"
}

func (a *App) uiURL() string { return fmt.Sprintf("http://127.0.0.1:%d/", a.cfg.Port) }

func startApp(dataDir string) (*App, context.CancelFunc, error) {
	ctx, cancel := context.WithCancel(context.Background())
	a := newApp(ctx, dataDir)
	if err := a.Start(); err != nil {
		cancel()
		return nil, nil, err
	}
	return a, cancel, nil
}

// tipLoop met à jour un texte d'état toutes les 2 s.
func tipLoop(a *App, set func(string)) {
	for {
		set(a.status())
		time.Sleep(2 * time.Second)
	}
}
