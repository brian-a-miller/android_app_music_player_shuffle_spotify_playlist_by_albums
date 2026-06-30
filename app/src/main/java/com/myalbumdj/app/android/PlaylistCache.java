package com.myalbumdj.app.android;

import java.util.Collections;
import java.util.List;

/**
 * In-memory cache of the user's playlists for the lifetime of the app process. Lets the user
 * leave and return to {@link ListSpotifyPlaylists} without re-querying Spotify each time.
 *
 * Cleared on logout (see MainActivity). The cache does not survive the process being killed.
 * TODO: persist to disk and/or add an explicit refresh if staleness becomes a concern.
 */
final class PlaylistCache {

    // volatile: written on a background thread, read on the main thread.
    private static volatile List<Playlist> playlists;

    private PlaylistCache() {
    }

    static boolean isCached() {
        return playlists != null;
    }

    static List<Playlist> get() {
        return playlists;
    }

    static void set(List<Playlist> value) {
        playlists = Collections.unmodifiableList(value);
    }

    static void clear() {
        playlists = null;
    }
}
