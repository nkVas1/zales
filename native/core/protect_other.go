// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

//go:build !android

package zalescore

// SetProtector exists on non-Android hosts only so the package builds and its
// platform-independent logic can be tested on a workstation.
func SetProtector(p Protector, _ string) error {
	swapProtector(p)
	return errNotAndroid
}
