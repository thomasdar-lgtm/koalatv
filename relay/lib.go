package main

// Bibliothèque du PC : films et épisodes téléchargés ou en attente, et le téléchargement lui-même.
// Un fichier à la fois (l'abonnement n'autorise qu'une connexion), en pause dès qu'un appareil
// lit en ligne. La fin du fichier d'abord (index des MKV), puis le reste d'un seul tenant :
// la lecture peut commencer pendant le téléchargement.

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"sync"
	"time"
)

const (
	stQueued  = "attente"
	stRunning = "cours"
	stDone    = "fini"
	stFailed  = "echec"
)

type Item struct {
	Key      string `json:"k"`
	Title    string `json:"t"`
	Icon     string `json:"i"`
	Sid      string `json:"sid,omitempty"`
	Sn       string `json:"sn,omitempty"`
	Kind     string `json:"kind"` // movie | series
	ID       string `json:"id"`
	Ext      string `json:"ext"`
	Path     string `json:"path"`
	Total    int64  `json:"tot"`
	Done     int64  `json:"done"`
	TailFrom int64  `json:"tf"`
	TailDone bool   `json:"td"`
	State    string `json:"st"`
	Err      string `json:"err,omitempty"`
	Added    int64  `json:"at"`
	Speed    int64  `json:"-"`
}

// Available : octets lisibles à partir de pos sans attendre.
func (it *Item) Available(pos int64) int64 {
	switch {
	case it.State == stDone && it.Total > 0:
		return max64(0, it.Total-pos)
	case pos < it.Done:
		return it.Done - pos
	case it.TailDone && it.TailFrom >= 0 && pos >= it.TailFrom && pos < it.Total:
		return it.Total - pos
	}
	return 0
}

func (it *Item) Have() int64 {
	if it.State == stDone {
		return it.Total
	}
	h := it.Done
	if it.TailDone && it.Total > 0 {
		h += it.Total - it.TailFrom
	}
	return h
}

func (it *Item) Percent() int {
	if it.Total <= 0 {
		return 0
	}
	p := int(it.Have() * 100 / it.Total)
	if p > 100 {
		p = 100
	}
	return p
}

func max64(a, b int64) int64 {
	if a > b {
		return a
	}
	return b
}

type Lib struct {
	mu       sync.Mutex
	items    map[string]*Item
	file     string
	changed  func()
	cancel   context.CancelFunc
	current  string
	paused   bool   // quelqu'un regarde en ligne, ou pause manuelle
	pauseWhy string // affiché sur la page
	wake     chan struct{}
	urlFor   func(it *Item) string
	dir      func() string
	free     func(string) int64
}

func newLib(file string) *Lib {
	l := &Lib{items: map[string]*Item{}, file: file, wake: make(chan struct{}, 1), changed: func() {}}
	if b, err := os.ReadFile(file); err == nil {
		var list []*Item
		if json.Unmarshal(b, &list) == nil {
			for _, it := range list {
				if it.State == stRunning {
					it.State = stQueued
				}
				l.items[it.Key] = it
			}
		}
	}
	return l
}

func (l *Lib) save() {
	l.mu.Lock()
	list := make([]*Item, 0, len(l.items))
	for _, it := range l.items {
		c := *it
		list = append(list, &c)
	}
	l.mu.Unlock()
	sort.Slice(list, func(a, b int) bool { return list[a].Added < list[b].Added })
	b, _ := json.MarshalIndent(list, "", " ")
	tmp := l.file + ".tmp"
	if os.WriteFile(tmp, b, 0o600) == nil {
		os.Rename(tmp, l.file)
	}
}

func (l *Lib) poke() {
	select {
	case l.wake <- struct{}{}:
	default:
	}
}

// Get : copie de l'élément (nil s'il n'existe pas).
func (l *Lib) Get(key string) *Item {
	l.mu.Lock()
	defer l.mu.Unlock()
	it := l.items[key]
	if it == nil {
		return nil
	}
	c := *it
	return &c
}

// Snapshot : copie de la liste, la plus récente d'abord.
func (l *Lib) Snapshot() []Item {
	l.mu.Lock()
	out := make([]Item, 0, len(l.items))
	for _, it := range l.items {
		out = append(out, *it)
	}
	l.mu.Unlock()
	sort.Slice(out, func(a, b int) bool { return out[a].Added > out[b].Added })
	return out
}

var badName = regexp.MustCompile(`[<>:"/\\|?*\x00-\x1f]+`)

func cleanName(s string) string {
	s = strings.Join(strings.Fields(badName.ReplaceAllString(s, " ")), " ")
	s = strings.TrimRight(s, ". ")
	if len([]rune(s)) > 90 {
		s = string([]rune(s)[:90])
	}
	if s == "" {
		s = "Sans titre"
	}
	return s
}

// Add met une demande en file (sans doublon).
func (l *Lib) Add(it *Item) bool {
	l.mu.Lock()
	if _, ok := l.items[it.Key]; ok || it.Key == "" || it.ID == "" {
		l.mu.Unlock()
		return false
	}
	if it.Ext == "" {
		it.Ext = "mkv"
	}
	sub := "Films"
	if it.Kind == "series" {
		sub = filepath.Join("Séries", cleanName(it.Sn))
	}
	it.Path = filepath.Join(l.dir(), sub, cleanName(it.Title)+" ["+it.Key+"]."+cleanName(it.Ext))
	it.State, it.Added, it.TailFrom = stQueued, time.Now().UnixMilli(), -1
	l.items[it.Key] = it
	l.mu.Unlock()
	l.save()
	l.changed()
	l.poke()
	return true
}

func (l *Lib) Remove(key string) {
	l.mu.Lock()
	it := l.items[key]
	delete(l.items, key)
	if l.current == key && l.cancel != nil {
		l.cancel()
	}
	l.mu.Unlock()
	if it != nil {
		go func() {
			// le fichier peut être encore ouvert un instant (téléchargement, lecture)
			for i := 0; i < 20; i++ {
				if os.Remove(it.Path) == nil || errors.Is(statErr(it.Path), os.ErrNotExist) {
					break
				}
				time.Sleep(500 * time.Millisecond)
			}
			os.Remove(filepath.Dir(it.Path)) // dossier de série vide
		}()
	}
	l.save()
	l.changed()
}

func statErr(p string) error { _, err := os.Stat(p); return err }

func (l *Lib) Retry(key string) {
	l.mu.Lock()
	if it := l.items[key]; it != nil && it.State == stFailed {
		it.State, it.Err = stQueued, ""
	}
	l.mu.Unlock()
	l.save()
	l.changed()
	l.poke()
}

func (l *Lib) PauseWhy() string { l.mu.Lock(); defer l.mu.Unlock(); return l.pauseWhy }

// SetPaused : pause (quelqu'un regarde en ligne, ou à la main) ou reprise.
func (l *Lib) SetPaused(p bool, why string) {
	l.mu.Lock()
	was := l.paused
	l.paused, l.pauseWhy = p, why
	if p && l.cancel != nil {
		l.cancel()
	}
	l.mu.Unlock()
	if was != p {
		l.changed()
	}
	if !p {
		l.poke()
	}
}

func (l *Lib) next() *Item {
	l.mu.Lock()
	defer l.mu.Unlock()
	if l.paused {
		return nil
	}
	var best *Item
	for _, it := range l.items {
		if it.State == stQueued && (best == nil || it.Added < best.Added) {
			best = it
		}
	}
	return best
}

// Run traite la file jusqu'à l'arrêt du programme.
func (l *Lib) Run(ctx context.Context, busy func(bool, string)) {
	for ctx.Err() == nil {
		it := l.next()
		if it == nil {
			busy(false, "")
			select {
			case <-ctx.Done():
				return
			case <-l.wake:
			case <-time.After(30 * time.Second):
			}
			continue
		}
		busy(true, it.Title)
		l.download(ctx, it)
	}
}

const tailMax = 24 << 20

var errPaused = errors.New("pause")

type noSpace struct{ msg string }

func (e noSpace) Error() string { return e.msg }

func (l *Lib) download(parent context.Context, it *Item) {
	ctx, cancel := context.WithCancel(parent)
	l.mu.Lock()
	l.cancel, l.current = cancel, it.Key
	it.State, it.Err = stRunning, ""
	l.mu.Unlock()
	l.save()
	l.changed()
	defer func() {
		cancel()
		l.mu.Lock()
		l.cancel, l.current = nil, ""
		it.Speed = 0
		l.mu.Unlock()
		l.save()
		l.changed()
	}()
	fails := 0
	for {
		err := l.step(ctx, it)
		l.mu.Lock()
		gone := l.items[it.Key] != it
		l.mu.Unlock()
		if gone {
			return
		}
		if err == nil {
			l.mu.Lock()
			it.State = stDone
			l.mu.Unlock()
			return
		}
		var ns noSpace
		if errors.As(err, &ns) {
			l.mu.Lock()
			it.State, it.Err = stFailed, ns.msg
			l.mu.Unlock()
			return
		}
		if ctx.Err() != nil {
			l.mu.Lock()
			if it.State == stRunning {
				it.State = stQueued
			}
			l.mu.Unlock()
			return
		}
		fails++
		if fails >= 8 {
			l.mu.Lock()
			it.State, it.Err = stFailed, err.Error()
			l.mu.Unlock()
			return
		}
		select {
		case <-ctx.Done():
		case <-time.After(time.Duration(fails*3) * time.Second):
		}
	}
}

func (l *Lib) request(ctx context.Context, it *Item, from, to int64) (*http.Response, error) {
	req, _ := http.NewRequestWithContext(ctx, "GET", l.urlFor(it), nil)
	req.Header.Set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) KoalaTV-Relais")
	req.Header.Set("Range", fmt.Sprintf("bytes=%d-%d", from, to))
	r, err := (&http.Client{Timeout: 0}).Do(req)
	if err != nil {
		return nil, err
	}
	if r.StatusCode >= 400 {
		r.Body.Close()
		return nil, fmt.Errorf("erreur du serveur (%d)", r.StatusCode)
	}
	return r, nil
}

func (l *Lib) step(ctx context.Context, it *Item) error {
	if it.Total <= 0 {
		r, err := l.request(ctx, it, 0, 0)
		if err != nil {
			return err
		}
		var total int64 = -1
		if cr := r.Header.Get("Content-Range"); strings.Contains(cr, "/") {
			fmt.Sscan(cr[strings.LastIndex(cr, "/")+1:], &total)
		} else if r.StatusCode == 200 {
			total = r.ContentLength
		}
		r.Body.Close()
		if total <= 0 {
			return errors.New("taille inconnue")
		}
		os.MkdirAll(filepath.Dir(it.Path), 0o755)
		if free := l.free(filepath.Dir(it.Path)); free > 0 && free < total+(500<<20) {
			return noSpace{fmt.Sprintf("Pas assez de place : %s nécessaires, %s libres", fmtSize(total), fmtSize(free))}
		}
		f, err := os.OpenFile(it.Path, os.O_CREATE|os.O_RDWR, 0o644)
		if err != nil {
			return noSpace{"Écriture impossible dans le dossier : " + err.Error()}
		}
		err = f.Truncate(total)
		f.Close()
		if err != nil {
			return noSpace{"Écriture impossible : " + err.Error()}
		}
		tail := total - min64(tailMax, max64(1<<20, total/50))
		if tail < 0 {
			tail = 0
		}
		l.mu.Lock()
		it.TailFrom = tail
		it.TailDone = tail == 0
		it.Total = total
		l.mu.Unlock()
		l.save()
	}
	if !it.TailDone {
		if err := l.copy(ctx, it, it.TailFrom, it.Total, nil); err != nil {
			return err
		}
		l.mu.Lock()
		it.TailDone = true
		l.mu.Unlock()
		l.save()
	}
	if it.Done < it.TailFrom {
		return l.copy(ctx, it, it.Done, it.TailFrom, func(p int64) { it.Done = p })
	}
	return nil
}

func min64(a, b int64) int64 {
	if a < b {
		return a
	}
	return b
}

// Certains serveurs envoient le début de chaque requête à pleine vitesse, puis brident.
// Quand la vitesse s'effondre par rapport au début de la requête, on la relance là où on en est
// (toujours une seule connexion à la fois). Si la relance ne rend pas la vitesse, on n'insiste plus.
const (
	reopenMinPeak = 3 << 20 // 3 Mo/s au début de la requête
	reopenRatio   = 4       // vitesse tombée sous le quart du début
)

var errReopen = errors.New("relance")

func (l *Lib) copy(ctx context.Context, it *Item, from, to int64, mark func(int64)) error {
	pos := from
	reopen := true
	for pos < to {
		start := pos
		t0 := time.Now()
		var err error
		pos, err = l.copyOnce(ctx, it, pos, to, mark, reopen)
		if err != errReopen {
			return err
		}
		// la relance n'a apporté presque rien en plus de la vitesse bridée : on arrête de relancer
		if time.Since(t0) > 20*time.Second && pos-start < int64(time.Since(t0).Seconds())*reopenMinPeak/2 {
			reopen = false
		}
	}
	return nil
}

func (l *Lib) copyOnce(ctx context.Context, it *Item, from, to int64, mark func(int64), reopen bool) (int64, error) {
	if from >= to {
		return from, nil
	}
	r, err := l.request(ctx, it, from, to-1)
	if err != nil {
		return from, err
	}
	defer r.Body.Close()
	if r.StatusCode != 206 && !(r.StatusCode == 200 && from == 0) {
		return from, fmt.Errorf("le serveur refuse la reprise (%d)", r.StatusCode)
	}
	f, err := os.OpenFile(it.Path, os.O_RDWR, 0o644)
	if err != nil {
		return from, noSpace{"Écriture impossible : " + err.Error()}
	}
	defer f.Close()
	buf := make([]byte, 512<<10)
	pos := from
	lastT, lastP := time.Now(), pos
	lastSave := time.Now()
	var peak int64
	slow := 0
	for pos < to {
		n, rerr := r.Body.Read(buf[:min64(int64(len(buf)), to-pos)])
		if n > 0 {
			if _, werr := f.WriteAt(buf[:n], pos); werr != nil {
				return pos, noSpace{"Écriture impossible : " + werr.Error()}
			}
			pos += int64(n)
			l.mu.Lock()
			if mark != nil {
				mark(pos)
			}
			if dt := time.Since(lastT); dt > time.Second {
				inst := (pos - lastP) * int64(time.Second) / int64(dt)
				if it.Speed <= 0 {
					it.Speed = inst
				} else {
					it.Speed = (it.Speed*7 + inst*3) / 10
				}
				lastT, lastP = time.Now(), pos
				l.mu.Unlock()
				l.changed()
				if inst > peak {
					peak = inst
				}
				if reopen && peak >= reopenMinPeak && inst*reopenRatio < peak {
					slow++
				} else {
					slow = 0
				}
				if slow >= 2 && pos < to {
					return pos, errReopen
				}
			} else {
				l.mu.Unlock()
			}
			if time.Since(lastSave) > 10*time.Second {
				lastSave = time.Now()
				l.save()
			}
		}
		if rerr == io.EOF {
			break
		}
		if rerr != nil {
			if ctx.Err() != nil {
				return pos, errPaused
			}
			return pos, rerr
		}
	}
	if pos < to {
		return pos, errors.New("connexion coupée")
	}
	return pos, nil
}

func fmtSize(b int64) string {
	switch {
	case b <= 0:
		return ""
	case b >= 1<<30:
		return strings.Replace(fmt.Sprintf("%.1f Go", float64(b)/(1<<30)), ".", ",", 1)
	default:
		return fmt.Sprintf("%d Mo", b>>20)
	}
}

// Avail : octets lisibles à partir de pos (sous verrou), état et taille.
func (l *Lib) Avail(key string, pos int64) (avail int64, total int64, state string, ok bool) {
	l.mu.Lock()
	defer l.mu.Unlock()
	it := l.items[key]
	if it == nil {
		return 0, 0, "", false
	}
	return it.Available(pos), it.Total, it.State, true
}
