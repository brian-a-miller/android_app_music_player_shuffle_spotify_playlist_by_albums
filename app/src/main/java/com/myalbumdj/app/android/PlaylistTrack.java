package com.myalbumdj.app.android;

/*
 * Author: Claude (Anthropic)
 * Last modified: 2026-06-20
 * Version log:
 *   2026-06-20 - Documented the `index` field (its position within the playlist context).
 */

/**
 * One track in a playlist: its Spotify URI (used to detect which row is currently playing), the
 * display label, and its index within the playlist context (used to start playback at this track).
 * Also carries album info and ordering data for the upcoming "shuffle by album" feature.
 * {@link #toString()} returns the label for list adapters.
 */
final class PlaylistTrack {

    final String uri;
    final String label;
    final int index; // position of this track within the playlist context, used to start playback here
    final Album album;
    final int trackNumber;
    final int discNumber;
    final long durationMs;

    PlaylistTrack(String uri, String label, int index, Album album,
                  int trackNumber, int discNumber, long durationMs) {
        this.uri = uri;
        this.label = label;
        this.index = index;
        this.album = album;
        this.trackNumber = trackNumber;
        this.discNumber = discNumber;
        this.durationMs = durationMs;
    }

    @Override
    public String toString() {
        return label;
    }
}
