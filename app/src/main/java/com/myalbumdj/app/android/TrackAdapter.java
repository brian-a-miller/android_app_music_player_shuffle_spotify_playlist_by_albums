package com.myalbumdj.app.android;

/*
 * Author: Claude (Anthropic)
 * Last modified: 2026-06-23
 * Version log:
 *   2026-06-20 - Added positionOfUri() so the list can scroll to the currently-playing track.
 *   2026-06-23 - Holds a mixed list of AlbumHeader and PlaylistTrack rows (two view types): album
 *                section headers (text only) plus the existing highlighted track rows.
 *   2026-06-23 - Track-row colors are now theme-aware (@color/track_row_bg/text): black-on-white in
 *                light mode, white-on-black in dark mode; the playing row uses them swapped.
 *   2026-06-23 - Album headers are now ~2.5x a track row tall with a square placeholder cover (a
 *                stable per-album color) in the left 1/3 and the title/artist in the right 2/3.
 *   2026-06-29 - Added a TODO to replace the placeholder color square with real album cover artwork
 *                (per Spotify branding/attribution rules).
 */

import android.content.Context;
import android.graphics.Color;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import java.util.List;

/**
 * Lists album-section headers and tracks. Header rows show the album's title/artist (text only for
 * now); track rows show the song label and highlight the currently-playing one (reversed colors).
 */
final class TrackAdapter extends ArrayAdapter<Object> {

    private static final int TYPE_TRACK = 0;
    private static final int TYPE_HEADER = 1;

    private String currentUri;
    // Header row height (~2.5x a track row) and the placeholder cover square side (1/3 screen width).
    private final int headerHeight;
    private final int coverSize;

    TrackAdapter(Context context, List<Object> rows) {
        super(context, 0, rows);
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        coverSize = metrics.widthPixels / 3;
        // "Standard track height" = the platform's preferred list item height (what track rows use).
        TypedValue tv = new TypedValue();
        int trackHeight = 0;
        if (context.getTheme().resolveAttribute(android.R.attr.listPreferredItemHeight, tv, true)) {
            trackHeight = (int) tv.getDimension(metrics);
        }
        if (trackHeight <= 0) {
            trackHeight = coverSize / 3; // sane fallback if the attribute can't be resolved
        }
        // 2.5x a track row, but at least big enough to contain the square cover.
        headerHeight = Math.max((int) (trackHeight * 2.5f), coverSize);
    }

    /** Sets the URI of the currently-playing track (or null) and repaints if it changed. */
    void setCurrentUri(String uri) {
        if (!TextUtils.equals(uri, currentUri)) {
            currentUri = uri;
            notifyDataSetChanged();
        }
    }

    /** Returns the list position of the track with the given URI, or -1 if it isn't shown. */
    int positionOfUri(String uri) {
        if (uri == null) {
            return -1;
        }
        for (int i = 0; i < getCount(); i++) {
            Object item = getItem(i);
            if (item instanceof PlaylistTrack && uri.equals(((PlaylistTrack) item).uri)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * A stable, pleasant pseudo-random color for an album's placeholder cover. Derived from the album
     * id so the same album always gets the same color (no flicker as rows recycle during scrolling).
     */
    private static int coverColor(Album album) {
        String key = album == null ? "" : (album.albumId != null ? album.albumId : album.albumName);
        int hash = key == null ? 0 : key.hashCode();
        float hue = Math.abs(hash) % 360;
        return Color.HSVToColor(new float[]{hue, 0.55f, 0.85f});
    }

    @Override
    public int getViewTypeCount() {
        return 2;
    }

    @Override
    public int getItemViewType(int position) {
        return getItem(position) instanceof AlbumHeader ? TYPE_HEADER : TYPE_TRACK;
    }

    @Override
    public boolean areAllItemsEnabled() {
        return false;
    }

    @Override
    public boolean isEnabled(int position) {
        // Header rows aren't tappable; only track rows are.
        return getItem(position) instanceof PlaylistTrack;
    }

    @NonNull
    @Override
    public View getView(int position, View convertView, @NonNull ViewGroup parent) {
        Object item = getItem(position);
        LayoutInflater inflater = LayoutInflater.from(getContext());
        if (item instanceof AlbumHeader) {
            View row = convertView != null ? convertView
                    : inflater.inflate(R.layout.list_item_album_header, parent, false);
            Album album = ((AlbumHeader) item).album;
            // Make the header ~2.5x a track row tall.
            ViewGroup.LayoutParams rowParams = row.getLayoutParams();
            if (rowParams == null) {
                rowParams = new AbsListView.LayoutParams(
                        AbsListView.LayoutParams.MATCH_PARENT, headerHeight);
            } else {
                rowParams.height = headerHeight;
            }
            row.setLayoutParams(rowParams);
            // Square placeholder cover in the left third, filled with a stable per-album color.
            View cover = row.findViewById(R.id.albumHeaderCover);
            ViewGroup.LayoutParams coverParams = cover.getLayoutParams();
            coverParams.width = coverSize;
            coverParams.height = coverSize;
            cover.setLayoutParams(coverParams);
            // TODO (future enhancement): replace this stable placeholder color square with the real
            //  album cover artwork. The Album already carries smallImageUrl/mediumImageUrl/largeImageUrl
            //  (parsed in ListSpotifyPlaylistTracks.parseAlbum); load the size closest to coverSize into
            //  an ImageView (likely an image-loading lib such as Glide/Coil, or a small async loader).
            //  Must follow Spotify's developer branding/attribution requirements for displaying their
            //  album art (e.g. don't crop/recolor the cover, show required Spotify attribution, link
            //  back to the content where required). Keep coverColor() as the fallback while loading or
            //  when an album has no images.
            cover.setBackgroundColor(coverColor(album));

            TextView title = row.findViewById(R.id.albumHeaderTitle);
            TextView artist = row.findViewById(R.id.albumHeaderArtist);
            title.setText(album != null ? album.albumName : "");
            artist.setText(album != null ? album.albumArtistNames : "");
            return row;
        }

        TextView view = (TextView) (convertView != null ? convertView
                : inflater.inflate(android.R.layout.simple_list_item_1, parent, false));
        PlaylistTrack track = (PlaylistTrack) item;
        view.setText(track != null ? track.label : "");
        boolean isPlaying = track != null && track.uri != null && track.uri.equals(currentUri);

        // Theme-aware colors: white-on-black in dark mode, black-on-white in light mode (see
        // res/values[-night]/colors.xml). The currently-playing row uses these two swapped.
        int rowBg = ContextCompat.getColor(getContext(), R.color.track_row_bg);
        int rowText = ContextCompat.getColor(getContext(), R.color.track_row_text);
        // TODO (future enhancement): give the currently-playing song its own distinct visual style
        //  (foreground color, background color, font/typeface/weight), AND give the *other* songs of
        //  the currently-selected album a second, different style so the whole album reads as one
        //  visual group. This will need the adapter to know the current album (pass it in alongside
        //  currentUri, e.g. setCurrentAlbum(Album)) and compare each row's track.album to it.
        if (isPlaying) {
            view.setBackgroundColor(rowText);
            view.setTextColor(rowBg);
        } else {
            view.setBackgroundColor(rowBg);
            view.setTextColor(rowText);
        }
        return view;
    }
}
