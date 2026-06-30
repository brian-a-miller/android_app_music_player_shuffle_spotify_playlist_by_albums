package com.myalbumdj.app.android;

import com.spotify.android.appremote.api.SpotifyAppRemote;

/**
 * Holds a single, app-scoped Spotify App Remote connection so it can be reused across activities.
 * Connecting once (and authorizing once) avoids the repeated connect/authorize churn that happens
 * when each screen connects and disconnects on its own. Cleared on logout (see MainActivity).
 */
final class SpotifyRemote {

    private static SpotifyAppRemote remote;

    private SpotifyRemote() {
    }

    static boolean isConnected() {
        return remote != null && remote.isConnected();
    }

    static SpotifyAppRemote get() {
        return remote;
    }

    static void set(SpotifyAppRemote value) {
        remote = value;
    }

    static void clear() {
        if (remote != null) {
            SpotifyAppRemote.disconnect(remote);
            remote = null;
        }
    }
}
