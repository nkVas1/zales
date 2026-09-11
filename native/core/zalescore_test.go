// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package zalescore

import (
	"encoding/json"
	"os"
	"strings"
	"testing"
)

type envelope struct {
	Success bool   `json:"success"`
	Error   string `json:"error"`
}

func TestInvokeConvertsPanicIntoFailureEnvelope(t *testing.T) {
	got := invokeWith(func(string) string { panic("boom") }, `{}`)

	var env envelope
	if err := json.Unmarshal([]byte(got), &env); err != nil {
		t.Fatalf("response is not JSON: %v\n%s", err, got)
	}
	if env.Success {
		t.Fatal("a panic must never be reported as success")
	}
	if !strings.HasPrefix(env.Error, "go panic: boom") {
		t.Fatalf("error should name the panic, got %q", env.Error)
	}
}

func TestInvokePassesThroughNormalResponses(t *testing.T) {
	const want = `{"success":true,"data":{"version":"x"},"error":""}`
	if got := invokeWith(func(string) string { return want }, `{}`); got != want {
		t.Fatalf("got %q, want %q", got, want)
	}
}

func TestInvokeRejectsGarbageWithoutPanicking(t *testing.T) {
	var env envelope
	if err := json.Unmarshal([]byte(Invoke("not json")), &env); err != nil {
		t.Fatalf("libXray must answer garbage with an envelope: %v", err)
	}
	if env.Success {
		t.Fatal("garbage request reported as success")
	}
}

func TestSetTunFdRejectsInvalidDescriptors(t *testing.T) {
	for _, fd := range []int{0, -1} {
		if err := SetTunFd(fd); err == nil {
			t.Fatalf("fd %d must be rejected", fd)
		}
	}
}

func TestTunFdRoundTrip(t *testing.T) {
	if err := SetTunFd(42); err != nil {
		t.Fatal(err)
	}
	if got := os.Getenv(tunFdEnv); got != "42" {
		t.Fatalf("env %s = %q, want 42", tunFdEnv, got)
	}
	if err := ClearTunFd(); err != nil {
		t.Fatal(err)
	}
	if _, present := os.LookupEnv(tunFdEnv); present {
		t.Fatal("ClearTunFd must remove the variable")
	}
}

type fakeProtector struct{ calls []int }

func (f *fakeProtector) Protect(fd int) bool {
	f.calls = append(f.calls, fd)
	return fd != 13
}

func TestForwarderAllowsSocketsWhenNoVpnIsUp(t *testing.T) {
	swapProtector(nil)
	if !(forwarder{}).ProtectFd(7) {
		t.Fatal("without a protector there is no loop to prevent; the socket must be allowed")
	}
}

func TestForwarderDelegatesToTheCurrentProtector(t *testing.T) {
	first, second := &fakeProtector{}, &fakeProtector{}

	swapProtector(first)
	(forwarder{}).ProtectFd(1)
	swapProtector(second)
	if (forwarder{}).ProtectFd(13) {
		t.Fatal("the current protector's refusal must be propagated")
	}
	swapProtector(nil)

	if len(first.calls) != 1 || len(second.calls) != 1 {
		t.Fatalf("stale protector was called: first=%v second=%v", first.calls, second.calls)
	}
}
