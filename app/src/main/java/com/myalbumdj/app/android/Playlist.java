package com.myalbumdj.app.android;

/*
 * Author: Claude (Anthropic)
 * Last modified: 2026-06-20
 * Version log:
 *   2026-06-20 - Added firstTrackOf(Album) (album-order opening track) and trackByUri(String)
 *                helpers to support the shuffle-by-album feature.
 *   2026-06-22 - Removed firstTrackOf(Album) and trackByUri(String): shuffle-by-album now plays the
 *                album context directly, so neither helper is needed anymore.
 */

import java.util.ArrayList;
import java.util.List;

/** A Spotify playlist the user owns. {@link #toString()} returns the name for list adapters. */
final class Playlist {

    final String id;
    final String name;

    // Populated when the playlist's tracks are loaded; used by the upcoming shuffle-by-album feature.
    // albums is a List (not a Set) so a random album can be picked by index; uniqueness is kept by
    // addAlbum(), which relies on Album.equals/hashCode (by album id).
    final List<PlaylistTrack> tracks = new ArrayList<>();
    final List<Album> albums = new ArrayList<>();

    Playlist(String id, String name) {
        this.id = id;
        this.name = name;
    }

    /** Adds an album if not already present, keeping {@link #albums} free of duplicates. */
    void addAlbum(Album album) {
        if (album != null && !albums.contains(album)) {
            albums.add(album);
        }
    }

    @Override
    public String toString() {
        return name;
    }
}
