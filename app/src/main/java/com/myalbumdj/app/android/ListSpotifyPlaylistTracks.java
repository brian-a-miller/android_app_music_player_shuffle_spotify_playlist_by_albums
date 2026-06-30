package com.myalbumdj.app.android;

/*
 * Author: Claude (Anthropic)
 * Last modified: 2026-06-20
 * Version log:
 *   2026-06-20 - Added the "Shuffle by Albums" button and shuffle-by-album logic: picks a random
 *                album, plays it from first to last track, and on album end jumps to another random
 *                album (handleShuffleProgress / startAlbum / pickRandomAlbum / onAlbumChanged).
 *                Manual playback (Play from Top, tapping a track) cancels shuffle mode.
 *   2026-06-20 - Auto-scroll the track list to the currently-playing/selected song, and size the
 *                bottom circular play/pause button to ~1/3 of the screen width.
 *   2026-06-20 - Use scalable white vector play/pause icons and pad the button so the symbol is
 *                large relative to the circle.
 *   2026-06-22 - Shuffle-by-album now plays the album *context* (full official album, shuffle off)
 *                instead of skipToIndex into the playlist. Fixes the intermittent "starts at track 2"
 *                bug caused by playlist index drift, and detects album end via the album URI / idle.
 *   2026-06-23 - parseAlbum now extracts cover-art URLs (small/medium/large) into the Album.
 *   2026-06-23 - Track list now shows an album-section header (text only) before each album's tracks
 *                (buildRows builds the mixed header/track list; headers are not tappable). Also
 *                captures album artist names and switches the row label to "<track#>. <title>".
 *   2026-06-23 - Added a TODO in startAlbum noting that album-context playback includes songs not in
 *                the playlist (a future option to honor playlist contents).
 *   2026-06-29 - Load tracks through SpotifyAuth (passed to SpotifyWebApi.getJson) so an expired
 *                access token is silently refreshed instead of failing the track load.
 *   2026-06-29 - Delegate shuffle-by-album to PlaybackService (a foreground service) so it keeps
 *                advancing albums while the app is backgrounded. Removed the in-activity shuffle
 *                state machine; manual playback now tells the service to stop. Requests
 *                POST_NOTIFICATIONS so the service notification can show on Android 13+.
 *   2026-06-29 - Added a TODO to wire up Previous/Next track transport buttons.
 */

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.spotify.android.appremote.api.ConnectionParams;
import com.spotify.android.appremote.api.Connector;
import com.spotify.android.appremote.api.SpotifyAppRemote;
import com.spotify.android.appremote.api.error.UserNotAuthorizedException;
import com.spotify.protocol.client.Subscription;
import com.spotify.protocol.types.PlayerState;
import com.spotify.sdk.android.auth.AuthorizationClient;
import com.spotify.sdk.android.auth.AuthorizationRequest;
import com.spotify.sdk.android.auth.AuthorizationResponse;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Lists the tracks of a single playlist (in playlist order) as "Track — Artist(s)".
 * Launched from {@link ListSpotifyPlaylists} with the playlist id and name as intent extras.
 */
public class ListSpotifyPlaylistTracks extends AppCompatActivity {

    private static final String TAG = "SpotifyPlayback";
    private static final int REQUEST_APP_REMOTE_AUTH = 0x20;
    private static final int REQUEST_POST_NOTIFICATIONS = 0x21;

    static final String EXTRA_PLAYLIST_ID = "playlist_id";
    static final String EXTRA_PLAYLIST_NAME = "playlist_name";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private TextView playlistNameTextView;
    private TextView trackCountTextView;
    private TextView statusTextView;
    private ListView tracksListView;
    private Button playFromTopButton;
    private Button shuffleByAlbumsButton;
    private ImageButton playPauseButton;
    private TrackAdapter adapter;

    private String playlistId;
    private String playlistName;
    private Playlist playlist;
    private boolean authRetryAttempted;
    private boolean connecting;
    private boolean awaitingAuth;
    private boolean tracksLoaded;
    private boolean lastIsPaused;
    private String currentPlayingUri;
    private PlaylistTrack pendingTrack;
    private Subscription<PlayerState> playerStateSubscription;
    // Last URI we auto-scrolled to, so we scroll once per track change instead of on every update.
    private String lastScrolledUri;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_list_spotify_playlist_tracks);
        playlistNameTextView = findViewById(R.id.playlistNameTextView);
        trackCountTextView = findViewById(R.id.trackCountTextView);
        statusTextView = findViewById(R.id.statusTextView);
        tracksListView = findViewById(R.id.tracksListView);
        playFromTopButton = findViewById(R.id.playFromTopButton);
        playFromTopButton.setOnClickListener(v -> playFromTop());
        shuffleByAlbumsButton = findViewById(R.id.shuffleByAlbumsButton);
        shuffleByAlbumsButton.setOnClickListener(v -> shuffleByAlbums());
        playPauseButton = findViewById(R.id.playPauseButton);
        playPauseButton.setOnClickListener(v -> togglePlayPause());
        // Make the bottom play/pause button a circle roughly one third of the screen width.
        int diameter = getResources().getDisplayMetrics().widthPixels / 3;
        ViewGroup.LayoutParams playPauseParams = playPauseButton.getLayoutParams();
        playPauseParams.width = diameter;
        playPauseParams.height = diameter;
        playPauseButton.setLayoutParams(playPauseParams);
        // Pad ~1/5 of the diameter on each side so the icon fills roughly the central 60% of the circle.
        int iconPadding = diameter / 5;
        playPauseButton.setPadding(iconPadding, iconPadding, iconPadding, iconPadding);

        // TODO (future enhancement): wire up Previous/Next track buttons (added in the layout, see
        //  activity_list_spotify_playlist_tracks.xml) that flank the play/pause button. Use the App
        //  Remote PlayerApi: previous -> SpotifyRemote.get().getPlayerApi().skipPrevious(); next ->
        //  skipNext(). Enable/disable them alongside playPauseButton in updatePlayButtonState(). Note:
        //  while shuffle-by-album is running (PlaybackService), a manual next/previous skips within the
        //  current album context, so decide whether that should also stop the shuffle service.

        adapter = new TrackAdapter(this, new ArrayList<>());
        tracksListView.setAdapter(adapter);
        tracksListView.setOnItemClickListener((parent, view, position, id) -> {
            Object item = adapter.getItem(position);
            if (item instanceof PlaylistTrack) {
                onTrackTapped((PlaylistTrack) item);
            }
        });

        playlistName = getIntent().getStringExtra(EXTRA_PLAYLIST_NAME);
        if (!TextUtils.isEmpty(playlistName)) {
            playlistNameTextView.setText(playlistName);
        }

        SpotifyAuth auth = new SpotifyAuth(this);
        playlistId = getIntent().getStringExtra(EXTRA_PLAYLIST_ID);
        if (!auth.isLoggedIn()) {
            showMessage(getString(R.string.playlists_not_logged_in));
            return;
        }
        if (TextUtils.isEmpty(playlistId)) {
            showMessage(getString(R.string.tracks_error, "missing playlist id"));
            return;
        }

        showMessage(getString(R.string.tracks_loading));
        loadTracks(playlistId, auth);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (SpotifyRemote.isConnected()) {
            // Reuse the existing app-scoped connection (authorized on a previous screen).
            updatePlayButtonState();
            subscribeToPlayerState();
        } else if (!awaitingAuth) {
            // Don't connect while an auth flow is pending; onActivityResult will reconnect.
            connectAppRemote();
        }
    }

    @Override
    protected void onStop() {
        // The connection is app-scoped (see SpotifyRemote), so we keep it alive across screens
        // and don't disconnect here. We do drop our player-state subscription, though.
        if (playerStateSubscription != null) {
            playerStateSubscription.cancel();
            playerStateSubscription = null;
        }
        connecting = false;
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    /**
     * Connects to the installed Spotify app; the play button is enabled only while connected.
     * If the Spotify app doesn't yet trust us for playback control, we run the Spotify-app SSO
     * login (which records the grant locally) and reconnect — see {@link #onActivityResult}.
     */
    private void connectAppRemote() {
        if (connecting || SpotifyRemote.isConnected()) {
            if (SpotifyRemote.isConnected()) {
                updatePlayButtonState();
                subscribeToPlayerState();
            }
            return;
        }
        connecting = true;
        // showAuthView(false): App Remote 0.8.0's built-in auth view doesn't work with the auth
        // library 1.2.5 we use, so we obtain authorization ourselves via openLoginActivity().
        // Connect with the application context so the connection isn't tied to this activity.
        ConnectionParams params = new ConnectionParams.Builder(BuildConfig.SPOTIFY_CLIENT_ID)
                .setRedirectUri(BuildConfig.SPOTIFY_REDIRECT_URI)
                .showAuthView(false)
                .build();
        SpotifyAppRemote.connect(getApplicationContext(), params, new Connector.ConnectionListener() {
            @Override
            public void onConnected(SpotifyAppRemote appRemote) {
                connecting = false;
                SpotifyRemote.set(appRemote);
                updatePlayButtonState();
                subscribeToPlayerState();
            }

            @Override
            public void onFailure(Throwable error) {
                connecting = false;
                playFromTopButton.setEnabled(false);
                Log.e(TAG, "App Remote connection failed: "
                        + error.getClass().getName() + ": " + error.getMessage(), error);
                if (error instanceof UserNotAuthorizedException && !authRetryAttempted) {
                    // The Spotify app hasn't been told to trust us yet. Run the SSO auth flow
                    // (goes through the installed Spotify app), then reconnect.
                    authRetryAttempted = true;
                    authorizeAppRemote();
                } else {
                    Toast.makeText(ListSpotifyPlaylistTracks.this,
                            getString(R.string.play_connect_failed, error.getMessage()),
                            Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    /** Authorizes playback control through the Spotify app's SSO (records the grant on-device). */
    private void authorizeAppRemote() {
        awaitingAuth = true;
        AuthorizationRequest request = new AuthorizationRequest.Builder(
                BuildConfig.SPOTIFY_CLIENT_ID,
                AuthorizationResponse.Type.TOKEN,
                BuildConfig.SPOTIFY_REDIRECT_URI)
                .setScopes(new String[]{"app-remote-control"})
                .build();
        AuthorizationClient.openLoginActivity(this, REQUEST_APP_REMOTE_AUTH, request);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent intent) {
        super.onActivityResult(requestCode, resultCode, intent);
        if (requestCode != REQUEST_APP_REMOTE_AUTH) {
            return;
        }
        awaitingAuth = false;
        AuthorizationResponse response = AuthorizationClient.getResponse(resultCode, intent);
        Log.d(TAG, "App Remote auth result: type=" + response.getType()
                + " error=" + response.getError());
        if (response.getType() == AuthorizationResponse.Type.TOKEN) {
            // Authorization granted; reconnect now that the Spotify app trusts us.
            connectAppRemote();
        } else {
            String reason = response.getType() == AuthorizationResponse.Type.ERROR
                    ? response.getError() : "authorization not granted";
            Toast.makeText(this, getString(R.string.play_connect_failed, reason),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void playFromTop() {
        if (!SpotifyRemote.isConnected()) {
            return;
        }
        // Manual playback takes over from shuffle-by-album.
        PlaybackService.stopShuffle(this);
        // Playing the playlist *context* starts at the first track and continues through the list.
        String playlistUri = "spotify:playlist:" + playlistId;
        SpotifyRemote.get().getPlayerApi().play(playlistUri)
                .setErrorCallback(error -> Toast.makeText(this,
                        getString(R.string.play_failed, error.getMessage()),
                        Toast.LENGTH_LONG).show());
    }

    /**
     * Turns on shuffle-by-album by handing the album IDs to {@link PlaybackService}. The service —
     * not this activity — runs the loop, so it keeps advancing to a new random album even while the
     * user is in another app (see the class docs on PlaybackService).
     *
     * <p>The album <em>context</em> plays each album's full official tracklist, which can include
     * songs the user deliberately left out of the playlist. (TODO, future: an option to honor
     * playlist contents — e.g. play only the album tracks that appear in the playlist.)
     */
    private void shuffleByAlbums() {
        if (!SpotifyRemote.isConnected() || playlist == null || playlist.albums.isEmpty()) {
            return;
        }
        ArrayList<String> albumIds = new ArrayList<>();
        for (Album album : playlist.albums) {
            if (!TextUtils.isEmpty(album.albumId)) {
                albumIds.add(album.albumId);
            }
        }
        if (albumIds.isEmpty()) {
            Toast.makeText(this, getString(R.string.shuffle_no_albums), Toast.LENGTH_SHORT).show();
            return;
        }
        requestNotificationPermissionIfNeeded();
        PlaybackService.startShuffle(this, albumIds, playlistName);
    }

    /**
     * Asks for POST_NOTIFICATIONS (Android 13+) so the foreground-service notification can show.
     * Playback still works if it's denied; only the notification is suppressed, so we don't block
     * shuffle on the result.
     */
    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_POST_NOTIFICATIONS);
        }
    }

    /** Enables the playback buttons only once the track list is on screen and we're connected. */
    private void updatePlayButtonState() {
        boolean ready = tracksLoaded && SpotifyRemote.isConnected();
        playFromTopButton.setEnabled(ready);
        // Shuffle-by-album needs at least one album to pick from.
        shuffleByAlbumsButton.setEnabled(ready && playlist != null && !playlist.albums.isEmpty());
        playPauseButton.setEnabled(ready);
        // Dim the icon button when disabled (its drawable doesn't grey out on its own).
        playPauseButton.setAlpha(ready ? 1f : 0.4f);
    }

    /** Keeps the play/pause button visible and its icon in sync with Spotify's playback state. */
    private void subscribeToPlayerState() {
        if (!SpotifyRemote.isConnected() || playerStateSubscription != null) {
            return;
        }
        playerStateSubscription = SpotifyRemote.get().getPlayerApi().subscribeToPlayerState();
        playerStateSubscription.setEventCallback(playerState -> {
            currentPlayingUri = playerState.track != null ? playerState.track.uri : null;
            if (playerState.track != null && !playerState.isPaused) {
                // Playback is live, so a "selected while paused" pick no longer applies.
                pendingTrack = null;
            }
            updateHighlight();
            // Shuffle-by-album is driven by PlaybackService now, not this screen's subscription.
            if (playerState.track == null) {
                playPauseButton.setVisibility(View.GONE);
                return;
            }
            lastIsPaused = playerState.isPaused;
            playPauseButton.setVisibility(View.VISIBLE);
            // ic_play_white is a solid right-facing triangle; ic_pause_white is two vertical bars.
            playPauseButton.setImageResource(lastIsPaused
                    ? R.drawable.ic_play_white
                    : R.drawable.ic_pause_white);
            playPauseButton.setContentDescription(getString(lastIsPaused
                    ? R.string.play_content_description
                    : R.string.pause_content_description));
        });
    }

    private void onTrackTapped(PlaylistTrack track) {
        if (track == null || !SpotifyRemote.isConnected()) {
            return;
        }
        if (track.uri != null && track.uri.equals(currentPlayingUri)) {
            // Tapping the currently-loaded song behaves like the play/pause button.
            togglePlayPause();
        } else if (currentPlayingUri != null && lastIsPaused) {
            // Playback is paused: don't start now. Select/highlight the song and wait for the
            // user to press the play button. Manually choosing a song takes over from shuffle.
            PlaybackService.stopShuffle(this);
            pendingTrack = track;
            updateHighlight();
        } else {
            // Something is playing (or nothing is loaded yet): start the tapped song right away.
            PlaybackService.stopShuffle(this);
            playTrackInPlaylist(track);
        }
    }

    /** Plays the playlist starting at the given track (from its beginning); clears any pending pick. */
    private void playTrackInPlaylist(PlaylistTrack track) {
        pendingTrack = null;
        String playlistUri = "spotify:playlist:" + playlistId;
        SpotifyRemote.get().getPlayerApi().skipToIndex(playlistUri, track.index)
                .setErrorCallback(error -> Toast.makeText(this,
                        getString(R.string.play_failed, error.getMessage()),
                        Toast.LENGTH_LONG).show());
    }

    /** Highlights the pending pick if there is one, otherwise the currently-playing track. */
    private void updateHighlight() {
        adapter.setCurrentUri(pendingTrack != null ? pendingTrack.uri : currentPlayingUri);
        scrollToHighlighted();
    }

    /** Scrolls the list so the highlighted (playing or selected) track is visible. */
    private void scrollToHighlighted() {
        String target = pendingTrack != null ? pendingTrack.uri : currentPlayingUri;
        if (target == null || target.equals(lastScrolledUri)) {
            return;
        }
        int position = adapter.positionOfUri(target);
        if (position >= 0) {
            tracksListView.smoothScrollToPosition(position);
            lastScrolledUri = target;
        }
    }

    private void togglePlayPause() {
        if (!SpotifyRemote.isConnected()) {
            return;
        }
        if (pendingTrack != null) {
            // A song was selected while paused; the play button starts that song.
            playTrackInPlaylist(pendingTrack);
            return;
        }
        // Spotify remembers the position; resume() continues where pause() left off.
        // The state subscription flips the icon once the change actually takes effect.
        if (lastIsPaused) {
            SpotifyRemote.get().getPlayerApi().resume();
        } else {
            SpotifyRemote.get().getPlayerApi().pause();
        }
    }

    private void loadTracks(String playlistId, SpotifyAuth auth) {
        executor.execute(() -> {
            try {
                Playlist loaded = new Playlist(playlistId, playlistName);
                // Counts every item (including skipped ones) so it matches the playlist's own
                // indexing, which is what skipToIndex() expects.
                int playlistIndex = 0;
                String url = SpotifyWebApi.BASE_URL + "/playlists/" + playlistId
                        + "/tracks?limit=100";
                while (url != null) {
                    JSONObject page = SpotifyWebApi.getJson(url, auth);
                    JSONArray items = page.getJSONArray("items");
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject track = items.getJSONObject(i).optJSONObject("track");
                        if (track == null) {
                            // Local files or removed tracks can come back null — skip them.
                            playlistIndex++;
                            continue;
                        }
                        String trackName = track.optString("name");
                        int trackNumber = track.optInt("track_number");
                        String label = String.format(Locale.US, "%d. %s", trackNumber, trackName);

                        Album album = parseAlbum(track.optJSONObject("album"));
                        loaded.tracks.add(new PlaylistTrack(
                                track.optString("uri"), label, playlistIndex, album,
                                trackNumber, track.optInt("disc_number"),
                                track.optLong("duration_ms")));
                        loaded.addAlbum(album);
                        playlistIndex++;
                    }
                    // Follow pagination until there are no more pages.
                    url = page.isNull("next") ? null : page.optString("next", null);
                }
                // Keep playlist order (no sorting).
                showResult(loaded);
            } catch (Exception e) {
                showMessageAsync(getString(R.string.tracks_error, String.valueOf(e.getMessage())));
            }
        });
    }

    /** Builds an {@link Album} from a track's simplified album object (null if absent). */
    private static Album parseAlbum(JSONObject albumJson) {
        if (albumJson == null) {
            return null;
        }
        List<String> artistIds = new ArrayList<>();
        JSONArray albumArtists = albumJson.optJSONArray("artists");
        if (albumArtists != null) {
            for (int i = 0; i < albumArtists.length(); i++) {
                JSONObject artist = albumArtists.optJSONObject(i);
                if (artist != null) {
                    artistIds.add(artist.optString("id"));
                }
            }
        }
        // Spotify returns 0-3 cover images; map them to small/medium/large by width.
        List<JSONObject> images = new ArrayList<>();
        JSONArray imagesJson = albumJson.optJSONArray("images");
        if (imagesJson != null) {
            for (int i = 0; i < imagesJson.length(); i++) {
                JSONObject image = imagesJson.optJSONObject(i);
                if (image != null) {
                    images.add(image);
                }
            }
        }
        Collections.sort(images, (a, b) -> Integer.compare(a.optInt("width"), b.optInt("width")));
        String smallImageUrl = images.isEmpty() ? "" : images.get(0).optString("url");
        String largeImageUrl = images.isEmpty() ? "" : images.get(images.size() - 1).optString("url");
        String mediumImageUrl = images.isEmpty() ? "" : images.get(images.size() / 2).optString("url");
        return new Album(
                albumJson.optString("uri"),
                albumJson.optString("id"),
                albumJson.optString("name"),
                albumJson.optString("album_type"),
                artistIds,
                joinArtists(albumJson.optJSONArray("artists")),
                smallImageUrl,
                mediumImageUrl,
                largeImageUrl);
    }

    private static String joinArtists(JSONArray artists) {
        if (artists == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < artists.length(); i++) {
            JSONObject artist = artists.optJSONObject(i);
            if (artist == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(artist.optString("name"));
        }
        return sb.toString();
    }

    /**
     * Turns the ordered tracks into the rows shown in the list: an {@link AlbumHeader} is inserted
     * before the first track of each album (albums are assumed contiguous), followed by its tracks.
     */
    private static List<Object> buildRows(List<PlaylistTrack> tracks) {
        List<Object> rows = new ArrayList<>();
        Album previousAlbum = null;
        for (PlaylistTrack track : tracks) {
            Album album = track.album;
            if (album != null && !album.equals(previousAlbum)) {
                rows.add(new AlbumHeader(album));
                previousAlbum = album;
            }
            rows.add(track);
        }
        return rows;
    }

    private void showResult(Playlist loaded) {
        mainHandler.post(() -> {
            playlist = loaded;
            List<PlaylistTrack> tracks = loaded.tracks;
            if (tracks.isEmpty()) {
                showMessage(getString(R.string.tracks_empty));
            } else {
                trackCountTextView.setText(getResources().getQuantityString(
                        R.plurals.track_count, tracks.size(), tracks.size()));
                trackCountTextView.setVisibility(View.VISIBLE);
                statusTextView.setVisibility(View.GONE);
                tracksListView.setVisibility(View.VISIBLE);
                adapter.clear();
                adapter.addAll(buildRows(tracks));
                adapter.notifyDataSetChanged();
                tracksLoaded = true;
                updatePlayButtonState();
            }
        });
    }

    private void showMessageAsync(String message) {
        mainHandler.post(() -> showMessage(message));
    }

    private void showMessage(String message) {
        trackCountTextView.setVisibility(View.GONE);
        statusTextView.setText(message);
        statusTextView.setVisibility(View.VISIBLE);
        tracksListView.setVisibility(View.GONE);
        tracksLoaded = false;
        updatePlayButtonState();
    }
}
