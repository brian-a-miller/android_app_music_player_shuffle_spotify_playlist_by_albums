package com.myalbumdj.app.android;

/*
 * Author: Claude (Anthropic)
 * Last modified: 2026-06-23
 * Version log:
 *   2026-06-23 - Created: a section-header row shown above the first track of each album in the list.
 */

/** A section header row for the track list, marking the start of an album's tracks. */
final class AlbumHeader {

    final Album album;

    AlbumHeader(Album album) {
        this.album = album;
    }
}
