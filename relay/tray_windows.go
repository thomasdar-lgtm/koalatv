//go:build windows

package main

// Icône près de l'horloge (zone de notification) et son menu, en Win32 direct.

import (
	"os"
	"path/filepath"
	"runtime"
	"syscall"
	"unsafe"
)

var (
	user32 = syscall.NewLazyDLL("user32.dll")

	pRegisterClass   = user32.NewProc("RegisterClassExW")
	pCreateWindow    = user32.NewProc("CreateWindowExW")
	pDefWindowProc   = user32.NewProc("DefWindowProcW")
	pGetMessage      = user32.NewProc("GetMessageW")
	pTranslate       = user32.NewProc("TranslateMessage")
	pDispatch        = user32.NewProc("DispatchMessageW")
	pPostQuit        = user32.NewProc("PostQuitMessage")
	pLoadImage       = user32.NewProc("LoadImageW")
	pCreatePopup     = user32.NewProc("CreatePopupMenu")
	pAppendMenu      = user32.NewProc("AppendMenuW")
	pTrackPopup      = user32.NewProc("TrackPopupMenu")
	pDestroyMenu     = user32.NewProc("DestroyMenu")
	pGetCursorPos    = user32.NewProc("GetCursorPos")
	pSetForeground   = user32.NewProc("SetForegroundWindow")
	pPostMessage     = user32.NewProc("PostMessageW")
	pNotifyIcon      = shell32.NewProc("Shell_NotifyIconW")
	pGetModuleHandle = kernel32.NewProc("GetModuleHandleW")
)

const (
	wmApp       = 0x8000
	wmTray      = wmApp + 1
	wmTip       = wmApp + 2
	wmCommand   = 0x0111
	wmDestroy   = 0x0002
	wmLButtonUp = 0x0202
	wmRButtonUp = 0x0205
	nimAdd      = 0
	nimModify   = 1
	nimDelete   = 2
	nifMessage  = 1
	nifIcon     = 2
	nifTip      = 4

	cmdOpen   = 1
	cmdFolder = 2
	cmdPause  = 3
	cmdQuit   = 4
)

type notifyIconData struct {
	cbSize           uint32
	hWnd             syscall.Handle
	uID              uint32
	uFlags           uint32
	uCallbackMessage uint32
	hIcon            syscall.Handle
	szTip            [128]uint16
	dwState          uint32
	dwStateMask      uint32
	szInfo           [256]uint16
	uVersion         uint32
	szInfoTitle      [64]uint16
	dwInfoFlags      uint32
	guidItem         [16]byte
	hBalloonIcon     syscall.Handle
}

type wndClassEx struct {
	cbSize        uint32
	style         uint32
	lpfnWndProc   uintptr
	cbClsExtra    int32
	cbWndExtra    int32
	hInstance     syscall.Handle
	hIcon         syscall.Handle
	hCursor       syscall.Handle
	hbrBackground syscall.Handle
	lpszMenuName  *uint16
	lpszClassName *uint16
	hIconSm       syscall.Handle
}

type msg struct {
	hwnd    syscall.Handle
	message uint32
	wParam  uintptr
	lParam  uintptr
	time    uint32
	pt      struct{ x, y int32 }
}

type tray struct {
	hwnd    syscall.Handle
	nid     notifyIconData
	tip     string
	onOpen  func()
	onDir   func()
	onPause func() bool // renvoie le nouvel état (true = en pause)
	paused  func() bool
	onQuit  func()
}

var theTray *tray

func u16(s string) *uint16 { p, _ := syscall.UTF16PtrFromString(s); return p }

func (t *tray) run(iconPng []byte, ico []byte, dataDir string) {
	runtime.LockOSThread()
	theTray = t
	inst, _, _ := pGetModuleHandle.Call(0)
	cls := wndClassEx{lpfnWndProc: syscall.NewCallback(wndProc), hInstance: syscall.Handle(inst), lpszClassName: u16("KoalaTVRelaisTray")}
	cls.cbSize = uint32(unsafe.Sizeof(cls))
	pRegisterClass.Call(uintptr(unsafe.Pointer(&cls)))
	h, _, _ := pCreateWindow.Call(0, uintptr(unsafe.Pointer(cls.lpszClassName)), uintptr(unsafe.Pointer(u16("KoalaTV Relais"))), 0, 0, 0, 0, 0, 0, 0, inst, 0)
	t.hwnd = syscall.Handle(h)

	icoPath := filepath.Join(dataDir, "koalatv.ico")
	os.WriteFile(icoPath, ico, 0o644)
	hi, _, _ := pLoadImage.Call(0, uintptr(unsafe.Pointer(u16(icoPath))), 1 /*IMAGE_ICON*/, 0, 0, 0x10|0x40 /*LR_LOADFROMFILE|LR_DEFAULTSIZE*/)

	t.nid.cbSize = uint32(unsafe.Sizeof(t.nid))
	t.nid.hWnd = t.hwnd
	t.nid.uID = 1
	t.nid.uFlags = nifMessage | nifIcon | nifTip
	t.nid.uCallbackMessage = wmTray
	t.nid.hIcon = syscall.Handle(hi)
	t.setTipLocked("KoalaTV Relais")
	pNotifyIcon.Call(nimAdd, uintptr(unsafe.Pointer(&t.nid)))

	var m msg
	for {
		r, _, _ := pGetMessage.Call(uintptr(unsafe.Pointer(&m)), 0, 0, 0)
		if int32(r) <= 0 {
			break
		}
		pTranslate.Call(uintptr(unsafe.Pointer(&m)))
		pDispatch.Call(uintptr(unsafe.Pointer(&m)))
	}
	pNotifyIcon.Call(nimDelete, uintptr(unsafe.Pointer(&t.nid)))
}

func (t *tray) setTipLocked(s string) {
	u, _ := syscall.UTF16FromString(s)
	if len(u) > 127 {
		u = append(u[:126], 0)
	}
	t.nid.szTip = [128]uint16{}
	copy(t.nid.szTip[:], u)
}

// SetTip : texte au survol de l'icône (depuis n'importe quel fil d'exécution).
func (t *tray) SetTip(s string) {
	if s == t.tip || t.hwnd == 0 {
		return
	}
	t.tip = s
	pPostMessage.Call(uintptr(t.hwnd), wmTip, 0, 0)
}

func (t *tray) showMenu() {
	m, _, _ := pCreatePopup.Call()
	add := func(id uintptr, label string) { pAppendMenu.Call(m, 0, id, uintptr(unsafe.Pointer(u16(label)))) }
	add(cmdOpen, "Ouvrir KoalaTV Relais")
	add(cmdFolder, "Ouvrir le dossier des vidéos")
	if t.paused() {
		add(cmdPause, "Reprendre les téléchargements")
	} else {
		add(cmdPause, "Mettre les téléchargements en pause")
	}
	pAppendMenu.Call(m, 0x800 /*MF_SEPARATOR*/, 0, 0)
	add(cmdQuit, "Quitter")
	var pt struct{ x, y int32 }
	pGetCursorPos.Call(uintptr(unsafe.Pointer(&pt)))
	pSetForeground.Call(uintptr(t.hwnd))
	pTrackPopup.Call(m, 0, uintptr(pt.x), uintptr(pt.y), 0, uintptr(t.hwnd), 0)
	pDestroyMenu.Call(m)
}

func wndProc(hwnd syscall.Handle, message uint32, wParam, lParam uintptr) uintptr {
	t := theTray
	switch message {
	case wmTray:
		switch lParam & 0xFFFF {
		case wmLButtonUp:
			go t.onOpen()
		case wmRButtonUp:
			t.showMenu()
		}
		return 0
	case wmTip:
		t.setTipLocked(t.tip)
		pNotifyIcon.Call(nimModify, uintptr(unsafe.Pointer(&t.nid)))
		return 0
	case wmCommand:
		switch wParam & 0xFFFF {
		case cmdOpen:
			go t.onOpen()
		case cmdFolder:
			go t.onDir()
		case cmdPause:
			go t.onPause()
		case cmdQuit:
			pNotifyIcon.Call(nimDelete, uintptr(unsafe.Pointer(&t.nid)))
			t.onQuit()
			pPostQuit.Call(0)
		}
		return 0
	case wmDestroy:
		pPostQuit.Call(0)
		return 0
	}
	r, _, _ := pDefWindowProc.Call(uintptr(hwnd), uintptr(message), wParam, lParam)
	return r
}
