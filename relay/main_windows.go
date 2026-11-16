//go:build windows

package main

import (
	"embed"
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

//go:embed koalatv.ico
var icoFile embed.FS

var pCreateMutex = kernel32.NewProc("CreateMutexW")

func installDir() string {
	return filepath.Join(os.Getenv("LOCALAPPDATA"), "Programs", "KoalaTV Relais")
}

// install : premier lancement (ou nouvelle version) depuis le dossier Téléchargements :
// copie dans le dossier des programmes, raccourci du menu Démarrer, démarrage avec Windows.
func install(self string) bool {
	dir := installDir()
	if strings.EqualFold(filepath.Dir(self), dir) {
		return false
	}
	// une version déjà lancée s'arrête pour être remplacée
	if port := runningPort(); port != "" {
		http.Post("http://127.0.0.1:"+port+"/api/quit", "application/json", strings.NewReader("{}"))
		time.Sleep(1500 * time.Millisecond)
	}
	os.MkdirAll(dir, 0o755)
	dst := filepath.Join(dir, "KoalaTV Relais.exe")
	if err := copyFile(self, dst); err != nil {
		return false
	}
	lnk := filepath.Join(os.Getenv("APPDATA"), `Microsoft\Windows\Start Menu\Programs\KoalaTV Relais.lnk`)
	ps := `$s=(New-Object -ComObject WScript.Shell).CreateShortcut('` + lnk + `');$s.TargetPath='` + dst + `';$s.WorkingDirectory='` + dir + `';$s.Description='KoalaTV Relais';$s.Save()`
	cmd := exec.Command("powershell", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command", ps)
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: 0x08000000}
	cmd.Run()
	exec.Command(dst, "--installe").Start()
	return true
}

// runningPort : port de la page de réglages d'une instance déjà lancée (d'après ses réglages).
func runningPort() string {
	b, err := os.ReadFile(filepath.Join(dataDirDefault(), "config.json"))
	if err != nil {
		return ""
	}
	s := string(b)
	i := strings.Index(s, `"port":`)
	if i < 0 {
		return ""
	}
	s = strings.TrimSpace(s[i+7:])
	j := strings.IndexAny(s, ",\n}")
	if j < 0 {
		return ""
	}
	return strings.TrimSpace(s[:j])
}

func copyFile(src, dst string) error {
	in, err := os.Open(src)
	if err != nil {
		return err
	}
	defer in.Close()
	tmp := dst + ".new"
	out, err := os.Create(tmp)
	if err != nil {
		return err
	}
	if _, err := io.Copy(out, in); err != nil {
		out.Close()
		return err
	}
	out.Close()
	os.Remove(dst)
	return os.Rename(tmp, dst)
}

func main() {
	self, _ := os.Executable()
	args := strings.Join(os.Args[1:], " ")
	if !strings.Contains(args, "--sur-place") && install(self) {
		return
	}
	// une seule instance : la seconde ouvre simplement la page de réglages
	_, _, e := pCreateMutex.Call(0, 0, uintptr(unsafe.Pointer(u16("KoalaTVRelais.Instance"))))
	if e == syscall.Errno(183) { // ERROR_ALREADY_EXISTS
		if port := runningPort(); port != "" {
			openBrowser("http://127.0.0.1:" + port + "/")
		}
		return
	}
	data := dataDirDefault()
	a, cancel, err := startApp(data)
	if err != nil {
		return
	}
	first := strings.Contains(args, "--installe")
	if first {
		// première installation : démarrage avec Windows activé
		if !autostartOn() {
			setAutostart(true)
		}
	} else if autostartOn() {
		setAutostart(true) // chemin à jour
	}
	if !strings.Contains(args, "--demarrage") || !a.linked() {
		openBrowser(a.uiURL())
	}
	ico, _ := icoFile.ReadFile("koalatv.ico")
	t := &tray{
		onOpen: func() { openBrowser(a.uiURL()) },
		onDir:  func() { openPath(a.cfg.Dir) },
		paused: func() bool { a.mu.Lock(); defer a.mu.Unlock(); return a.manualPause },
		onPause: func() bool {
			a.mu.Lock()
			a.manualPause = !a.manualPause
			p := a.manualPause
			a.mu.Unlock()
			a.updatePause()
			return p
		},
		onQuit: func() { cancel(); keepAwake(false) },
	}
	a.onQuit = func() { cancel(); keepAwake(false); os.Exit(0) }
	go tipLoop(a, t.SetTip)
	t.run(nil, ico, data)
}
