package io.github.nkvas1.zales.tunnel.service;

import io.github.nkvas1.zales.tunnel.service.ITunnelCallback;
import io.github.nkvas1.zales.tunnel.service.TunnelStatus;

interface ITunnelService {
    /** Registers for updates and returns the state as it is right now. */
    TunnelStatus register(ITunnelCallback callback);

    void unregister(ITunnelCallback callback);

    /** Opens the path. Requires VPN consent to have been granted already. */
    void open();

    void close();

    /** Tries again after a failure, starting the strategy race from the top. */
    void retry();

    /**
     * Walks the probe ladder and reports each rung through onDiagnosis.
     * Runs here, in the tunnel process, because only this side holds the key
     * and only this side can keep its own sockets out of the tunnel.
     */
    void diagnose();

    void cancelDiagnosis();

    /**
     * Whether Russian sites go around the tunnel. Pushed across rather than
     * shared through a file: preferences shared between processes have never
     * been reliable on Android.
     */
    void setBypassDomestic(boolean value);

    /**
     * The stored key has changed — chosen from the list, or the one in use was
     * forgotten. Picks up the new one without taking the interface down.
     */
    void usingNewKey();
}
