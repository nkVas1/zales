// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

//go:build android

package zalescore

import (
	"sync"

	libXray "github.com/xtls/libxray"
)

var (
	registerOnce sync.Once
	dnsOnce      sync.Once
	dnsErr       error
)

// SetProtector routes every socket Xray dials or listens on through
// VpnService.protect, so traffic to the server leaves via the real network
// instead of looping back into the tunnel.
//
// dnsServer is an IP endpoint ("1.1.1.1:53") for Go's process resolver, whose
// sockets are protected the same way. Pass a nil protector when the VPN goes
// down; the registration itself is permanent by design (see forwarder).
func SetProtector(p Protector, dnsServer string) error {
	swapProtector(p)
	registerOnce.Do(func() {
		libXray.RegisterDialerController(forwarder{})
		libXray.RegisterListenerController(forwarder{})
	})
	if p == nil {
		return nil
	}
	// SetDNS remembers the previous resolver on every call; calling it twice
	// would make that "previous" our own resolver. Install exactly once.
	dnsOnce.Do(func() {
		dnsErr = libXray.SetDNS(forwarder{}, dnsServer)
	})
	return dnsErr
}
