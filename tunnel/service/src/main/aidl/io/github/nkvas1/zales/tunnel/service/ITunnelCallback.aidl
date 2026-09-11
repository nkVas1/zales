package io.github.nkvas1.zales.tunnel.service;

import io.github.nkvas1.zales.tunnel.service.TunnelStatus;

/** Pushed from the :tunnel process to the UI. One-way: the tunnel never waits on the UI. */
oneway interface ITunnelCallback {
    void onStatus(in TunnelStatus status);
}
