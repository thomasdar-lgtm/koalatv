//go:build windows

package main

// Particularités de Windows : mot de passe chiffré par Windows (DPAPI), disques, démarrage avec Windows,
// pas de mise en veille pendant un téléchargement ou une lecture, ouverture du navigateur et des dossiers.

import (
	"encoding/base64"
	"os"
	"path/filepath"
	"runtime"
	"syscall"
	"unsafe"
)

var (
	crypt32  = syscall.NewLazyDLL("crypt32.dll")
	kernel32 = syscall.NewLazyDLL("kernel32.dll")
	advapi32 = syscall.NewLazyDLL("advapi32.dll")
	shell32  = syscall.NewLazyDLL("shell32.dll")

	pCryptProtect   = crypt32.NewProc("CryptProtectData")
	pCryptUnprotect = crypt32.NewProc("CryptUnprotectData")
	pLocalFree      = kernel32.NewProc("LocalFree")
	pDiskFree       = kernel32.NewProc("GetDiskFreeSpaceExW")
	pDrives         = kernel32.NewProc("GetLogicalDrives")
	pDriveType      = kernel32.NewProc("GetDriveTypeW")
	pExecState      = kernel32.NewProc("SetThreadExecutionState")
	pRegCreate      = advapi32.NewProc("RegCreateKeyExW")
	pRegSet         = advapi32.NewProc("RegSetValueExW")
	pRegDelete      = advapi32.NewProc("RegDeleteValueW")
	pRegQuery       = advapi32.NewProc("RegQueryValueExW")
	pRegClose       = advapi32.NewProc("RegCloseKey")
	pShellExec      = shell32.NewProc("ShellExecuteW")
)

type blob struct {
	n uint32
	p *byte
}

func newBlob(b []byte) *blob {
	if len(b) == 0 {
		return &blob{}
	}
	return &blob{n: uint32(len(b)), p: &b[0]}
}

func (b *blob) bytes() []byte {
	out := make([]byte, b.n)
	copy(out, unsafe.Slice(b.p, b.n))
	return out
}

// protect : chiffré par Windows pour l'utilisateur courant (illisible sur un autre PC ou un autre compte).
func protect(s string) string {
	if s == "" {
		return ""
	}
	var out blob
	r, _, _ := pCryptProtect.Call(uintptr(unsafe.Pointer(newBlob([]byte(s)))), 0, 0, 0, 0, 0, uintptr(unsafe.Pointer(&out)))
	if r == 0 {
		return ""
	}
	defer pLocalFree.Call(uintptr(unsafe.Pointer(out.p)))
	return "dpapi:" + base64.StdEncoding.EncodeToString(out.bytes())
}

func unprotect(s string) string {
	if len(s) < 6 || s[:6] != "dpapi:" {
		return ""
	}
	raw, err := base64.StdEncoding.DecodeString(s[6:])
	if err != nil {
		return ""
	}
	var out blob
	r, _, _ := pCryptUnprotect.Call(uintptr(unsafe.Pointer(newBlob(raw))), 0, 0, 0, 0, 0, uintptr(unsafe.Pointer(&out)))
	if r == 0 {
		return ""
	}
	defer pLocalFree.Call(uintptr(unsafe.Pointer(out.p)))
	return string(out.bytes())
}

func dataDirDefault() string {
	return filepath.Join(os.Getenv("APPDATA"), "KoalaTV Relais")
}

func diskFree(root string) (free, total int64) {
	var f, t, tf uint64
	p, _ := syscall.UTF16PtrFromString(root)
	r, _, _ := pDiskFree.Call(uintptr(unsafe.Pointer(p)), uintptr(unsafe.Pointer(&f)), uintptr(unsafe.Pointer(&t)), uintptr(unsafe.Pointer(&tf)))
	if r == 0 {
		return 0, 0
	}
	return int64(f), int64(t)
}

func freeSpace(dir string) int64 {
	for d := dir; ; d = filepath.Dir(d) {
		if _, err := os.Stat(d); err == nil {
			f, _ := diskFree(d)
			return f
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
	free  int64
}

// drives : disques fixes et amovibles, avec leur place libre.
func drives() []Drive {
	var out []Drive
	mask, _, _ := pDrives.Call()
	for i := 0; i < 26; i++ {
		if mask&(1<<uint(i)) == 0 {
			continue
		}
		root := string(rune('A'+i)) + `:\`
		p, _ := syscall.UTF16PtrFromString(root)
		t, _, _ := pDriveType.Call(uintptr(unsafe.Pointer(p)))
		if t != 2 && t != 3 { // amovible, fixe
			continue
		}
		f, tot := diskFree(root)
		if tot == 0 {
			continue
		}
		out = append(out, Drive{Path: root + "KoalaTV", Free: fmtSize(f), Total: fmtSize(tot), free: f})
	}
	return out
}

// defaultDir : le disque qui a le plus de place.
func defaultDir() string {
	best := Drive{Path: `C:\KoalaTV`}
	for _, d := range drives() {
		if d.free > best.free {
			best = d
		}
	}
	return best.Path
}

const runKey = `Software\Microsoft\Windows\CurrentVersion\Run`
const runName = "KoalaTV Relais"

func regOpenRun() (syscall.Handle, bool) {
	var h syscall.Handle
	k, _ := syscall.UTF16PtrFromString(runKey)
	r, _, _ := pRegCreate.Call(uintptr(syscall.HKEY_CURRENT_USER), uintptr(unsafe.Pointer(k)), 0, 0, 0, 0xF003F, 0, uintptr(unsafe.Pointer(&h)), 0)
	return h, r == 0
}

func setAutostart(on bool) error {
	h, ok := regOpenRun()
	if !ok {
		return syscall.EINVAL
	}
	defer pRegClose.Call(uintptr(h))
	name, _ := syscall.UTF16PtrFromString(runName)
	if !on {
		pRegDelete.Call(uintptr(h), uintptr(unsafe.Pointer(name)))
		return nil
	}
	exe, _ := os.Executable()
	val, _ := syscall.UTF16FromString(`"` + exe + `" --demarrage`)
	r, _, _ := pRegSet.Call(uintptr(h), uintptr(unsafe.Pointer(name)), 0, 1, uintptr(unsafe.Pointer(&val[0])), uintptr(len(val)*2))
	if r != 0 {
		return syscall.EINVAL
	}
	return nil
}

func autostartOn() bool {
	h, ok := regOpenRun()
	if !ok {
		return false
	}
	defer pRegClose.Call(uintptr(h))
	name, _ := syscall.UTF16PtrFromString(runName)
	r, _, _ := pRegQuery.Call(uintptr(h), uintptr(unsafe.Pointer(name)), 0, 0, 0, 0)
	return r == 0
}

// keepAwake : empêche la mise en veille tant que le PC télécharge ou envoie une vidéo.
var awake = make(chan bool, 8)

func init() {
	go func() {
		runtime.LockOSThread() // l'état de veille appartient au fil d'exécution
		for on := range awake {
			const continuous, system = 0x80000000, 0x00000001
			if on {
				pExecState.Call(continuous | system)
			} else {
				pExecState.Call(continuous)
			}
		}
	}()
}

func keepAwake(on bool) {
	select {
	case awake <- on:
	default:
	}
}

func shellOpen(target string) {
	verb, _ := syscall.UTF16PtrFromString("open")
	t, _ := syscall.UTF16PtrFromString(target)
	pShellExec.Call(0, uintptr(unsafe.Pointer(verb)), uintptr(unsafe.Pointer(t)), 0, 0, 1)
}

func openPath(p string) {
	os.MkdirAll(p, 0o755)
	shellOpen(p)
}

func openBrowser(u string) { shellOpen(u) }
