//go:build !windows

package main

// Version de développement (Linux) : mêmes fonctions, sans les particularités de Windows.

import (
	"os"
	"path/filepath"
	"syscall"
)

func protect(s string) string   { return s }
func unprotect(s string) string { return s }

func dataDirDefault() string {
	h, _ := os.UserHomeDir()
	return filepath.Join(h, ".koalatv-relais")
}

func defaultDir() string {
	h, _ := os.UserHomeDir()
	return filepath.Join(h, "KoalaTV")
}

func freeSpace(dir string) int64 {
	for d := dir; ; d = filepath.Dir(d) {
		var st syscall.Statfs_t
		if syscall.Statfs(d, &st) == nil {
			return int64(st.Bavail) * int64(st.Bsize)
		}
		if d == filepath.Dir(d) {
			return 0
		}
	}
}

type Drive struct {
	Path  string `json:"path"`
	Free  string `json:"free"`
	Total string `json:"total"`
}

func drives() []Drive {
	h, _ := os.UserHomeDir()
	return []Drive{{Path: filepath.Join(h, "KoalaTV"), Free: fmtSize(freeSpace(h))}}
}

func setAutostart(on bool) error { return nil }
func autostartOn() bool          { return false }
func keepAwake(on bool)          {}
func openPath(p string)          {}
func openBrowser(u string)       {}
