package com.myalbumdj.app.android;

/*
 * Author: Claude (Anthropic)
 * Last modified: 2026-06-23
 * Version log:
 *   2026-06-23 - Added cover-art image URL fields (small / medium / large).
 *   2026-06-23 - Added albumArtistNames (joined display string) for album-section headers.
 */

import java.util.List;
import java.util.Objects;

/**
 * A Spotify album as referenced by a playlist track (the simplified album object). Captured for the
 * "shuffle by album" feature. Equality is by album id so albums dedupe correctly in a Set.
 */
final class Album {

    final String albumUri;
    final String albumId;
    final String albumName;
    final String albumType;
    final List<String> artistIds;
    // The album artists' display names, joined (e.g. "Miles Davis"); empty if unknown.
    final String albumArtistNames;
    // Cover-art URLs by size (empty string if the album had no image at that size).
    final String smallImageUrl;
    final String mediumImageUrl;
    final String largeImageUrl;

    Album(String albumUri, String albumId, String albumName, String albumType,
          List<String> artistIds, String albumArtistNames, String smallImageUrl,
          String mediumImageUrl, String largeImageUrl) {
        this.albumUri = albumUri;
        this.albumId = albumId;
        this.albumName = albumName;
        this.albumType = albumType;
        this.artistIds = artistIds;
        this.albumArtistNames = albumArtistNames;
        this.smallImageUrl = smallImageUrl;
        this.mediumImageUrl = mediumImageUrl;
        this.largeImageUrl = largeImageUrl;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Album)) {
            return false;
        }
        return Objects.equals(albumId, ((Album) o).albumId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(albumId);
    }

    @Override
    public String toString() {
        return albumName;
    }
}
