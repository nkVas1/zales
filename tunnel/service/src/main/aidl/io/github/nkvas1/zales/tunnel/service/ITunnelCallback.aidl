package io.github.nkvas1.zales.tunnel.service;

import io.github.nkvas1.zales.tunnel.service.TunnelStatus;
import io.github.nkvas1.zales.tunnel.service.DiagnosisStatus;

/** Pushed from the :tunnel process to the UI. One-way: the tunnel never waits on the UI. */
oneway interface ITunnelCallback {
    void onStatus(in TunnelStatus status);

    /** One rung of the path check, as it happens. Sent only while a check is running. */
    void onDiagnosis(in DiagnosisStatus diagnosis);
}
