package com.myalbumdj.app.android;

/*
 * Author: Claude (Anthropic)
 * Last modified: 2026-06-29
 * Version log:
 *   2026-06-29 - Use SpotifyAuth for the login check and pass it to SpotifyWebApi.getJson so Web API
 *                calls auto-refresh an expired access token instead of failing.
 *   2026-06-29 - Added a TODO for a playlist filter/search field (substring match on name).
 */

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Lists the playlists the logged-in user has created, sorted alphabetically (case-insensitive,
 * ascending). Tapping a playlist opens {@link ListSpotifyPlaylistTracks} for that playlist.
 */
public class ListSpotifyPlaylists extends AppCompatActivity {

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private TextView statusTextView;
    private ListView playlistsListView;
    private ArrayAdapter<Playlist> adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_list_spotify_playlists);
        statusTextView = findViewById(R.id.statusTextView);
        playlistsListView = findViewById(R.id.playlistsListView);

        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>());
        playlistsListView.setAdapter(adapter);
        // TODO (future enhancement): add a filter/search field (see the layout's playlistFilter TODO).
        //  Keep the full loaded list separately, and on each text change rebuild the adapter's contents
        //  with only the playlists whose name contains the entered text as a case-insensitive substring
        //  (e.g. name.toLowerCase(Locale...).contains(query.toLowerCase(...))). Showing all when the
        //  field is empty. The in-memory PlaylistCache already holds the full list to filter against.
        playlistsListView.setOnItemClickListener((parent, view, position, id) -> {
            Playlist playlist = adapter.getItem(position);
            if (playlist != null) {
                openTracks(playlist);
            }
        });

        // Reuse the in-memory cache if we've already loaded playlists this session, so returning
        // to this screen doesn't re-query Spotify.
        if (PlaylistCache.isCached()) {
            showResult(PlaylistCache.get());
            return;
        }

        SpotifyAuth auth = new SpotifyAuth(this);
        if (!auth.isLoggedIn()) {
            showMessage(getString(R.string.playlists_not_logged_in));
            return;
        }

        showMessage(getString(R.string.playlists_loading));
        loadPlaylists(auth);
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private void openTracks(Playlist playlist) {
        Intent intent = new Intent(this, ListSpotifyPlaylistTracks.class);
        intent.putExtra(ListSpotifyPlaylistTracks.EXTRA_PLAYLIST_ID, playlist.id);
        intent.putExtra(ListSpotifyPlaylistTracks.EXTRA_PLAYLIST_NAME, playlist.name);
        startActivity(intent);
    }

    private void loadPlaylists(SpotifyAuth auth) {
        executor.execute(() -> {
            try {
                // Identify the current user so we can keep only the playlists they created;
                // /me/playlists also returns playlists the user merely follows.
                String userId = SpotifyWebApi
                        .getJson(SpotifyWebApi.BASE_URL + "/me", auth)
                        .getString("id");

                // The playlist-read-private scope (requested at login) means private playlists
                // owned by the user are included here, not just public ones.
                // TODO: if only public playlists ever appear, the access token is missing the
                // playlist-read-private scope — re-authenticate requesting that scope.
                List<Playlist> playlists = new ArrayList<>();
                String url = SpotifyWebApi.BASE_URL + "/me/playlists?limit=50";
                while (url != null) {
                    JSONObject page = SpotifyWebApi.getJson(url, auth);
                    JSONArray items = page.getJSONArray("items");
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject playlist = items.getJSONObject(i);
                        JSONObject owner = playlist.optJSONObject("owner");
                        if (owner != null && userId.equals(owner.optString("id"))) {
                            playlists.add(new Playlist(
                                    playlist.optString("id"), playlist.optString("name")));
                        }
                    }
                    // Follow pagination until there are no more pages.
                    url = page.isNull("next") ? null : page.optString("next", null);
                }

                Collections.sort(playlists, (a, b) ->
                        String.CASE_INSENSITIVE_ORDER.compare(a.name, b.name));
                PlaylistCache.set(playlists);
                showResult(playlists);
            } catch (Exception e) {
                showMessageAsync(getString(R.string.playlists_error, String.valueOf(e.getMessage())));
            }
        });
    }

    private void showResult(List<Playlist> playlists) {
        mainHandler.post(() -> {
            if (playlists.isEmpty()) {
                showMessage(getString(R.string.playlists_empty));
            } else {
                statusTextView.setVisibility(View.GONE);
                playlistsListView.setVisibility(View.VISIBLE);
                adapter.clear();
                adapter.addAll(playlists);
                adapter.notifyDataSetChanged();
            }
        });
    }

    private void showMessageAsync(String message) {
        mainHandler.post(() -> showMessage(message));
    }

    private void showMessage(String message) {
        statusTextView.setText(message);
        statusTextView.setVisibility(View.VISIBLE);
        playlistsListView.setVisibility(View.GONE);
    }
}
