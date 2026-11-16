package main

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"
)

const version = "1.0.0"

type Config struct {
	Server string `json:"server"`
	User   string `json:"user"`
	Pass   string `json:"pass"` // chiffré par Windows (protect)
	Dir    string `json:"dir"`
	Port   int    `json:"port"`
	Token  string `json:"token"`
	ID     string `json:"id"`
	Name   string `json:"name"`
}

type App struct {
	mu          sync.Mutex
	cfg         Config
	pass        string // en clair, en mémoire seulement
	dataDir     string
	lib         *Lib
	fb          *FB
	pairCode    string
	ctx         context.Context
	stopLinked  context.CancelFunc
	watching    []string // appareils qui lisent en ligne
	manualPause bool
	readers     int
	downloading string
	pubDirty    bool
	lastLib     string
	onQuit      func()
}

func randHex(n int) string { b := make([]byte, n); rand.Read(b); return hex.EncodeToString(b) }

func newApp(ctx context.Context, dataDir string) *App {
	a := &App{ctx: ctx, dataDir: dataDir}
	os.MkdirAll(dataDir, 0o700)
	if b, err := os.ReadFile(filepath.Join(dataDir, "config.json")); err == nil {
		json.Unmarshal(b, &a.cfg)
		a.pass = unprotect(a.cfg.Pass)
	}
	if a.cfg.Port == 0 {
		a.cfg.Port = 47610
	}
	if a.cfg.Token == "" {
		a.cfg.Token = randHex(16)
	}
	if a.cfg.ID == "" {
		a.cfg.ID = "pc" + randHex(5)
	}
	if a.cfg.Name == "" {
		h, _ := os.Hostname()
		a.cfg.Name = "PC " + h
	}
	if a.cfg.Dir == "" {
		a.cfg.Dir = defaultDir()
	}
	a.saveConfig()
	a.lib = newLib(filepath.Join(dataDir, "bibliotheque.json"))
	a.lib.dir = func() string { a.mu.Lock(); defer a.mu.Unlock(); return a.cfg.Dir }
	a.lib.free = freeSpace
	a.lib.urlFor = func(it *Item) string {
		a.mu.Lock()
		defer a.mu.Unlock()
		kind := "movie"
		if it.Kind == "series" {
			kind = "series"
		}
		return strings.TrimRight(a.cfg.Server, "/") + "/" + kind + "/" + a.cfg.User + "/" + a.pass + "/" + it.ID + "." + it.Ext
	}
	a.lib.changed = func() { a.mu.Lock(); a.pubDirty = true; a.mu.Unlock() }
	return a
}

func (a *App) saveConfig() {
	a.mu.Lock()
	c := a.cfg
	c.Pass = protect(a.pass)
	a.mu.Unlock()
	b, _ := json.MarshalIndent(c, "", " ")
	os.WriteFile(filepath.Join(a.dataDir, "config.json"), b, 0o600)
}

func (a *App) linked() bool { a.mu.Lock(); defer a.mu.Unlock(); return a.cfg.User != "" && a.pass != "" }

// Start : serveur local, puis liaison au compte (code) ou travail normal.
func (a *App) Start() error {
	ln, err := net.Listen("tcp", ":"+itoa(a.cfg.Port))
	if err != nil {
		// port occupé : un autre au hasard
		ln, err = net.Listen("tcp", ":0")
		if err != nil {
			return err
		}
		a.cfg.Port = ln.Addr().(*net.TCPAddr).Port
		a.saveConfig()
	}
	if p := ln.Addr().(*net.TCPAddr).Port; p != a.cfg.Port {
		a.cfg.Port = p
		a.saveConfig()
	}
	go http.Serve(ln, a.routes())
	go a.loop()
	return nil
}

func (a *App) loop() {
	for a.ctx.Err() == nil {
		if !a.linked() {
			a.waitPairing()
			continue
		}
		ctx, cancel := context.WithCancel(a.ctx)
		a.mu.Lock()
		a.stopLinked = cancel
		a.fb = newFB(accountHash(a.cfg.Server, a.cfg.User, a.pass))
		a.mu.Unlock()
		go a.fb.Listen(ctx, "q", func() { a.pullQueue() })
		go a.fb.Listen(ctx, "p", func() { a.pullPresence() })
		go a.lib.Run(ctx, func(on bool, title string) { a.setDownloading(on, title) })
		a.pullQueue()
		a.pullPresence()
		tick := time.NewTicker(3 * time.Second)
		n := 0
		for ctx.Err() == nil {
			select {
			case <-ctx.Done():
			case <-tick.C:
				n++
				a.mu.Lock()
				dirty := a.pubDirty
				a.pubDirty = false
				a.mu.Unlock()
				if dirty || n%20 == 0 {
					a.publish()
				}
				if n%10 == 0 {
					a.pullPresence()
					a.pullQueue()
				}
				a.heartbeat()
			}
		}
		tick.Stop()
	}
}

// waitPairing affiche un code et attend les identifiants envoyés par un appareil déjà connecté.
func (a *App) waitPairing() {
	a.mu.Lock()
	if a.pairCode == "" {
		a.pairCode = newPairCode()
	}
	code := a.pairCode
	a.mu.Unlock()
	for a.ctx.Err() == nil && !a.linked() {
		if c, err := pairReceive(code); err == nil {
			a.mu.Lock()
			a.cfg.Server, a.cfg.User, a.pass = strings.TrimRight(c.Server, "/"), c.User, c.Pass
			a.pairCode = ""
			a.mu.Unlock()
			a.saveConfig()
			return
		}
		select {
		case <-a.ctx.Done():
		case <-time.After(2 * time.Second):
		}
	}
}

func (a *App) unlink() {
	a.mu.Lock()
	fb := a.fb
	a.cfg.Server, a.cfg.User, a.pass = "", "", ""
	stop := a.stopLinked
	a.mu.Unlock()
	if fb != nil {
		fb.Delete("r")
		fb.Delete("rl")
		fb.Delete("p/" + a.cfg.ID)
	}
	a.lib.SetPaused(true, "")
	if stop != nil {
		stop()
	}
	a.lib.SetPaused(false, "")
	a.saveConfig()
}

// Demandes des applis : q/<clé> = {kind, id, ext, t, i, sid, sn} ; {del: 1} pour retirer.
func (a *App) pullQueue() {
	var q map[string]struct {
		Kind string `json:"kind"`
		ID   string `json:"id"`
		Ext  string `json:"ext"`
		T    string `json:"t"`
		I    string `json:"i"`
		Sid  string `json:"sid"`
		Sn   string `json:"sn"`
		Del  int    `json:"del"`
		Retry int   `json:"retry"`
	}
	if a.fb == nil || a.fb.Get("q", &q) != nil {
		return
	}
	for k, v := range q {
		switch {
		case v.Del == 1:
			a.lib.Remove(k)
		case v.Retry == 1:
			a.lib.Retry(k)
		default:
			a.lib.Add(&Item{Key: k, Kind: v.Kind, ID: v.ID, Ext: v.Ext, Title: v.T, Icon: v.I, Sid: v.Sid, Sn: v.Sn})
		}
		a.fb.Delete("q/" + k)
	}
	if len(q) > 0 {
		a.publish()
	}
}

// Lecture en ligne sur un appareil (présence récente, ni locale ni un téléchargement) : le PC se met en pause.
func (a *App) pullPresence() {
	var p map[string]struct {
		N  string `json:"n"`
		T  string `json:"t"`
		At int64  `json:"at"`
		L  int    `json:"l"`
		Dl int    `json:"dl"`
	}
	if a.fb == nil || a.fb.Get("p", &p) != nil {
		return
	}
	var who []string
	now := time.Now().UnixMilli()
	for k, v := range p {
		if k == a.cfg.ID || v.L == 1 || v.Dl == 1 || now-v.At > 75_000 {
			continue
		}
		who = append(who, v.N)
	}
	sort.Strings(who)
	a.mu.Lock()
	a.watching = who
	a.mu.Unlock()
	a.updatePause()
}

func (a *App) updatePause() {
	a.mu.Lock()
	why := ""
	if a.manualPause {
		why = "Mis en pause"
	} else if len(a.watching) > 0 {
		why = "En pause : lecture en cours sur " + strings.Join(a.watching, ", ")
	}
	a.mu.Unlock()
	a.lib.SetPaused(why != "", why)
}

func (a *App) setDownloading(on bool, title string) {
	a.mu.Lock()
	a.downloading = ""
	if on {
		a.downloading = title
	}
	a.pubDirty = true
	a.mu.Unlock()
	keepAwake(on || a.readersNow() > 0)
	a.heartbeat()
}

func (a *App) readersNow() int { a.mu.Lock(); defer a.mu.Unlock(); return a.readers }

// reading : lecture en cours depuis le PC (le PC ne doit pas se mettre en veille).
func (a *App) reading(d int) {
	a.mu.Lock()
	a.readers += d
	busy := a.readers > 0 || a.downloading != ""
	a.mu.Unlock()
	keepAwake(busy)
}

// heartbeat : présence « téléchargement » du PC, vue par les applis (sans les bloquer).
func (a *App) heartbeat() {
	a.mu.Lock()
	fb, title, id := a.fb, a.downloading, a.cfg.ID
	a.mu.Unlock()
	if fb == nil {
		return
	}
	if title == "" {
		fb.Delete("p/" + id)
		return
	}
	fb.Put("p/"+id, map[string]any{"n": a.cfg.Name, "t": "Téléchargement : " + title, "at": time.Now().UnixMilli(), "dl": 1})
}

// lanIPs : adresses du PC sur le réseau local.
func lanIPs() []string {
	var out []string
	ifs, _ := net.Interfaces()
	for _, i := range ifs {
		if i.Flags&net.FlagUp == 0 || i.Flags&net.FlagLoopback != 0 {
			continue
		}
		addrs, _ := i.Addrs()
		for _, ad := range addrs {
			if n, ok := ad.(*net.IPNet); ok && n.IP.To4() != nil && n.IP.IsPrivate() {
				out = append(out, n.IP.String())
			}
		}
	}
	return out
}

// publish : adresse du PC (r) et état de la bibliothèque (rl), lus par les applis.
func (a *App) publish() {
	a.mu.Lock()
	fb := a.fb
	cfg := a.cfg
	a.mu.Unlock()
	if fb == nil {
		return
	}
	fb.Put("r", map[string]any{
		"n": cfg.Name, "ip": lanIPs(), "port": cfg.Port, "tok": cfg.Token, "at": time.Now().UnixMilli(),
		"v": version, "free": freeSpace(cfg.Dir), "pause": a.lib.PauseWhy(),
	})
	rl := map[string]any{}
	for _, it := range a.lib.Snapshot() {
		e := map[string]any{"t": it.Title, "i": it.Icon, "st": it.State, "pct": it.Percent(), "tot": it.Total, "at": it.Added, "ext": it.Ext}
		if it.Sid != "" {
			e["sid"], e["sn"] = it.Sid, it.Sn
		}
		if it.Err != "" {
			e["err"] = it.Err
		}
		if it.Speed > 0 {
			e["sp"] = it.Speed
		}
		rl[it.Key] = e
	}
	b, _ := json.Marshal(rl)
	if string(b) == a.lastLib {
		return
	}
	if fb.Put("rl", rl) == nil {
		a.lastLib = string(b)
	}
}

type uiItem struct {
	Item
	Pct  int    `json:"pct"`
	Size string `json:"size"`
	Pace string `json:"pace"`
}

func (a *App) state() map[string]any {
	a.mu.Lock()
	cfg, code, watching, manual, dl := a.cfg, a.pairCode, a.watching, a.manualPause, a.downloading
	linked := cfg.User != "" && a.pass != ""
	a.mu.Unlock()
	items := []uiItem{}
	for _, it := range a.lib.Snapshot() {
		u := uiItem{Item: it, Pct: it.Percent(), Size: fmtSize(it.Total)}
		if it.State == stRunning && it.Speed > 0 {
			left := it.Total - it.Have()
			u.Pace = paceText(it.Speed, left)
		}
		items = append(items, u)
	}
	disp := ""
	if len(code) == 8 {
		disp = code[:4] + " " + code[4:]
	}
	return map[string]any{
		"v": version, "linked": linked, "code": disp, "server": hostOnly(cfg.Server), "user": cfg.User,
		"dir": cfg.Dir, "free": fmtSize(freeSpace(cfg.Dir)), "name": cfg.Name, "ips": lanIPs(), "port": cfg.Port,
		"items": items, "pause": a.lib.PauseWhy(), "manual": manual, "watching": watching, "dl": dl, "autostart": autostartOn(),
	}
}

func hostOnly(s string) string {
	s = strings.TrimPrefix(strings.TrimPrefix(s, "http://"), "https://")
	if i := strings.IndexByte(s, '/'); i >= 0 {
		s = s[:i]
	}
	return s
}

func paceText(speed, left int64) string {
	mbs := float64(speed) / (1 << 20)
	rate := strings.Replace(fmtFloat(mbs)+" Mo/s", ".", ",", 1)
	if left <= 0 {
		return rate
	}
	s := left / speed
	switch {
	case s < 60:
		return rate + " · moins d'une minute"
	case s < 3600:
		return rate + " · encore " + itoa(int(s/60)) + " min"
	default:
		m := int(s / 60 % 60)
		mm := itoa(m)
		if m < 10 {
			mm = "0" + mm
		}
		return rate + " · encore " + itoa(int(s/3600)) + " h " + mm
	}
}

func fmtFloat(f float64) string {
	if f >= 10 {
		return itoa(int(f))
	}
	return strings.TrimRight(strings.TrimRight(strconvF(f), "0"), ".")
}
