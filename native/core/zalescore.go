// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

// Package zalescore is the only Go package Zales binds into Kotlin.
//
// Every Go dependency of the app — libXray, Xray-core, gVisor — is compiled
// into this package by a single gomobile invocation, because one process can
// host only one Go runtime (docs/adr/0003-single-go-runtime.md).
//
// The surface is intentionally tiny: a panic-safe Invoke, the TUN descriptor
// hand-off, and socket protection. Everything else goes through Invoke's JSON
// protocol so the Kotlin side never depends on generated gomobile types.
package zalescore

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"runtime/debug"
	"strconv"
	"sync"

	libXray "github.com/xtls/libxray"
)

// tunFdEnv is read by Xray-core's Android TUN inbound when it starts.
//
// It must be set from Go: the Go runtime snapshots the process environment
// once at load time, so an Os.setenv from Kotlin after the library is loaded
// is invisible here.
const tunFdEnv = "xray.tun.fd"

// maxStackInError bounds how much of a panic stack is returned to Kotlin.
const maxStackInError = 4096

// Invoke forwards a libXray JSON request and never lets a Go panic escape.
//
// A panic crossing the gomobile boundary aborts the whole :tunnel process
// without any diagnostic. Converting it into libXray's normal failure envelope
// lets the UI say "something broke inside me" instead of silently vanishing.
func Invoke(requestJSON string) string {
	return invokeWith(libXray.Invoke, requestJSON)
}

func invokeWith(fn func(string) string, requestJSON string) (response string) {
	defer func() {
		if r := recover(); r != nil {
			response = failureEnvelope(fmt.Sprintf("go panic: %v\n%s", r, truncate(debug.Stack(), maxStackInError)))
		}
	}()
	return fn(requestJSON)
}

// SetTunFd hands the VpnService TUN descriptor to Xray's TUN inbound.
//
// Call it before every runXray: each VpnService.Builder.establish() yields a
// new descriptor. Xray neither duplicates nor closes it, so ownership stays
// with the Kotlin ParcelFileDescriptor, which must outlive the running core.
func SetTunFd(fd int) error {
	if fd <= 0 {
		return fmt.Errorf("invalid tun fd %d", fd)
	}
	return os.Setenv(tunFdEnv, strconv.Itoa(fd))
}

// ClearTunFd removes the descriptor after the core stops, so a stale number
// can never be picked up by the next start.
func ClearTunFd() error {
	return os.Unsetenv(tunFdEnv)
}

// Protector is implemented in Kotlin by delegating to VpnService.protect.
type Protector interface {
	Protect(fd int) bool
}

var (
	protectorMu sync.RWMutex
	protector   Protector
)

// forwarder is the one controller ever registered with Xray.
//
// Xray appends dialer controllers to a list and offers no way to remove them.
// Registering a fresh controller per connection would leave stale ones bound to
// dead VpnService instances, whose protect() returns false and breaks every
// later dial. So a single forwarder is registered once per process, and it
// delegates to whichever protector is current.
type forwarder struct{}

// ProtectFd satisfies libXray.DialerController.
func (forwarder) ProtectFd(fd int) bool {
	protectorMu.RLock()
	p := protector
	protectorMu.RUnlock()
	if p == nil {
		// No Zales VPN is up, so no routing loop is possible: allow the socket.
		return true
	}
	return p.Protect(fd)
}

func swapProtector(p Protector) {
	protectorMu.Lock()
	protector = p
	protectorMu.Unlock()
}

var errNotAndroid = errors.New("socket protection is only available on Android")

func failureEnvelope(message string) string {
	body, err := json.Marshal(struct {
		Success bool   `json:"success"`
		Data    any    `json:"data"`
		Error   string `json:"error"`
	}{Success: false, Data: nil, Error: message})
	if err != nil {
		return `{"success":false,"data":null,"error":"failed to encode failure envelope"}`
	}
	return string(body)
}

func truncate(b []byte, limit int) string {
	if len(b) <= limit {
		return string(b)
	}
	return string(b[:limit]) + "…"
}
