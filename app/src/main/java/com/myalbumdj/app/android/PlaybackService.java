package com.myalbumdj.app.android;

/*
 * Author: Claude (Anthropic)
 * Last modified: 2026-06-29
 * Version log:
 *   2026-06-29 - New: foreground service that runs the shuffle-by-album loop. Moving the loop out of
 *                the activity keeps it (and the process) alive while the user is in other apps, so an
 *                album that ends in the background automatically starts the next random album.
 *   2026-06-29 - Added a class-doc TODO to back the service with a MediaSession later; dropped the
 *                always-true SDK_INT >= O guard in createNotificationChannel (minSdk is 26).
 *   2026-06-29 - Added a TODO to post an OS notification naming each new album as it starts.
 */

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

import com.spotify.android.appremote.api.ConnectionParams;
import com.spotify.android.appremote.api.Connector;
import com.spotify.android.appremote.api.SpotifyAppRemote;
import com.spotify.protocol.client.Subscription;
import com.spotify.protocol.types.PlayerState;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Foreground service that keeps shuffle-by-album playing while the app is in the background.
 *
 * <p>The shuffle loop used to live in {@link ListSpotifyPlaylistTracks}, but an activity's
 * player-state subscription is cancelled the moment the user leaves the screen (and the OS may kill
 * the whole process), so an album that ended in the background never triggered the next one. A
 * foreground service runs independently of any screen and keeps the process alive, so the
 * "album ended → start next random album" logic keeps firing.
 *
 * <p>The service is told only the set of album IDs to shuffle among. It plays each album's full
 * official tracklist (the album <em>context</em>, shuffle off) and, when one ends, jumps to another
 * random album. It reuses the app-scoped App Remote connection in {@link SpotifyRemote}.
 *
 * <p>TODO (future): back this foreground service with a {@link android.media.session.MediaSession}
 *  (or media3 {@code MediaSessionService}). The {@code mediaPlayback} foreground-service type is the
 *  honest fit for "continuously controlling playback" and works for sideloaded/personal use, but
 *  Google Play requires that type to be backed by an active MediaSession. Adding one would also give
 *  us proper lock-screen / media transport controls instead of the plain notification we build here.
 */
public class PlaybackService extends Service {

    private static final String TAG = "SpotifyPlayback";

    static final String ACTION_START = "com.myalbumdj.app.android.action.START_SHUFFLE";
    static final String ACTION_STOP = "com.myalbumdj.app.android.action.STOP_SHUFFLE";
    private static final String EXTRA_ALBUM_IDS = "album_ids";
    private static final String EXTRA_PLAYLIST_NAME = "playlist_name";

    private static final String CHANNEL_ID = "album_shuffle_playback";
    private static final int NOTIFICATION_ID = 1;

    private final Random random = new Random();

    private List<String> albumIds;
    private String currentAlbumId;
    private String currentAlbumUri;
    // True between asking Spotify to start an album and seeing it actually playing; while set we
    // ignore the transient "wrong album" player-state events that occur during the switch.
    private boolean awaitingAlbumStart;
    private Subscription<PlayerState> playerStateSubscription;

    /** Starts (or restarts) shuffle-by-album in the foreground service. */
    static void startShuffle(Context context, ArrayList<String> albumIds, String playlistName) {
        Intent intent = new Intent(context, PlaybackService.class)
                .setAction(ACTION_START)
                .putStringArrayListExtra(EXTRA_ALBUM_IDS, albumIds)
                .putExtra(EXTRA_PLAYLIST_NAME, playlistName);
        ContextCompat.startForegroundService(context, intent);
    }

    /** Stops shuffle-by-album and tears the service down. Harmless if it isn't running. */
    static void stopShuffle(Context context) {
        Intent intent = new Intent(context, PlaybackService.class).setAction(ACTION_STOP);
        // Use a normal startService for the stop signal; onStartCommand handles teardown.
        context.startService(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_START.equals(action)) {
            albumIds = intent.getStringArrayListExtra(EXTRA_ALBUM_IDS);
            String playlistName = intent.getStringExtra(EXTRA_PLAYLIST_NAME);
            if (albumIds == null || albumIds.isEmpty()) {
                stopEverything();
                return START_NOT_STICKY;
            }
            // Become a foreground service immediately (required within a few seconds of starting).
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(playlistName),
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                            ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK : 0);
            ensureConnectedThenStart();
        } else if (ACTION_STOP.equals(action)) {
            stopEverything();
        }
        // If the OS kills us, redeliver the last start intent so we know which albums to resume.
        return START_REDELIVER_INTENT;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        // Started service only; nothing binds to it.
        return null;
    }

    @Override
    public void onDestroy() {
        cancelSubscription();
        super.onDestroy();
    }

    /**
     * Uses the shared App Remote connection if it's live, otherwise connects (no authorization is
     * attempted here — that needs an activity, and the screen that launched shuffle has already
     * authorized). Once connected, begins shuffling.
     */
    private void ensureConnectedThenStart() {
        if (SpotifyRemote.isConnected()) {
            subscribeAndStart();
            return;
        }
        ConnectionParams params = new ConnectionParams.Builder(BuildConfig.SPOTIFY_CLIENT_ID)
                .setRedirectUri(BuildConfig.SPOTIFY_REDIRECT_URI)
                .showAuthView(false)
                .build();
        SpotifyAppRemote.connect(getApplicationContext(), params, new Connector.ConnectionListener() {
            @Override
            public void onConnected(SpotifyAppRemote appRemote) {
                SpotifyRemote.set(appRemote);
                subscribeAndStart();
            }

            @Override
            public void onFailure(Throwable error) {
                Log.e(TAG, "Shuffle service could not connect App Remote: " + error.getMessage(), error);
                // Can't recover without a user-facing auth flow; give up cleanly.
                stopEverything();
            }
        });
    }

    private void subscribeAndStart() {
        subscribeToPlayerState();
        startAlbum(pickRandomAlbum(null));
    }

    /** Watches playback and advances to a new album when the current one ends. */
    private void subscribeToPlayerState() {
        if (!SpotifyRemote.isConnected() || playerStateSubscription != null) {
            return;
        }
        playerStateSubscription = SpotifyRemote.get().getPlayerApi().subscribeToPlayerState();
        playerStateSubscription.setEventCallback(playerState -> {
            String playingTrackUri = playerState.track != null ? playerState.track.uri : null;
            String playingAlbumUri = (playerState.track != null && playerState.track.album != null)
                    ? playerState.track.album.uri : null;
            handleShuffleProgress(playingTrackUri, playingAlbumUri);
        });
    }

    /**
     * Detects when the current album has finished — either playback stopped (album context
     * exhausted) or Spotify moved on to a different album (e.g. autoplay) — and jumps to a new random
     * album. The {@link #awaitingAlbumStart} guard ignores the transient updates that happen while
     * the album we just requested is still loading.
     */
    private void handleShuffleProgress(String playingTrackUri, String playingAlbumUri) {
        if (awaitingAlbumStart) {
            if (currentAlbumUri != null && currentAlbumUri.equals(playingAlbumUri)) {
                awaitingAlbumStart = false;
            }
            return;
        }
        if (playingTrackUri == null) {
            onAlbumChanged();
        } else if (playingAlbumUri != null && !playingAlbumUri.equals(currentAlbumUri)) {
            onAlbumChanged();
        }
    }

    /** The current album finished — pick another random album (avoiding an immediate repeat). */
    private void onAlbumChanged() {
        startAlbum(pickRandomAlbum(currentAlbumId));
    }

    /** Returns a random album ID, avoiding {@code exclude} when another choice exists. */
    private String pickRandomAlbum(@Nullable String exclude) {
        String albumId = albumIds.get(random.nextInt(albumIds.size()));
        if (albumId.equals(exclude) && albumIds.size() > 1) {
            // Re-roll once so the same album doesn't immediately repeat.
            albumId = albumIds.get(random.nextInt(albumIds.size()));
        }
        return albumId;
    }

    /** Plays an album's full official tracklist from the top by starting its context (shuffle off). */
    private void startAlbum(String albumId) {
        if (TextUtils.isEmpty(albumId) || !SpotifyRemote.isConnected()) {
            return;
        }
        currentAlbumId = albumId;
        currentAlbumUri = "spotify:album:" + albumId;
        awaitingAlbumStart = true;
        // TODO (future enhancement): when a new album starts, post an Android notification naming the
        //  album (artist + title) so the user sees it in their OS notification list. This service only
        //  receives album IDs today, so pass album names/artists too (e.g. a parallel String[] extra,
        //  or switch the START extra to a parcelable Album list) and update the ongoing foreground
        //  notification here (or post a separate, auto-cancel one). Best to fire it once the album is
        //  confirmed playing - i.e. where awaitingAlbumStart flips false in handleShuffleProgress -
        //  rather than on the play() request, to avoid notifying for an album that failed to start.
        SpotifyRemote.get().getPlayerApi().setShuffle(false);
        SpotifyRemote.get().getPlayerApi().play(currentAlbumUri)
                .setErrorCallback(error ->
                        Log.e(TAG, "Shuffle service failed to play album: " + error.getMessage()));
    }

    private void cancelSubscription() {
        if (playerStateSubscription != null) {
            playerStateSubscription.cancel();
            playerStateSubscription = null;
        }
    }

    private void stopEverything() {
        cancelSubscription();
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void createNotificationChannel() {
        // Notification channels are required since API 26 (O), which is our minSdk.
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.shuffle_channel_name),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.shuffle_channel_description));
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private Notification buildNotification(String playlistName) {
        Intent stopIntent = new Intent(this, PlaybackService.class).setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(this, 0, stopIntent,
                PendingIntent.FLAG_IMMUTABLE);
        // Tapping the body reopens the app.
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent openPending = PendingIntent.getActivity(this, 0, openIntent,
                PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.shuffle_notification_title))
                .setContentText(TextUtils.isEmpty(playlistName)
                        ? getString(R.string.shuffle_notification_text_generic)
                        : getString(R.string.shuffle_notification_text, playlistName))
                .setSmallIcon(R.drawable.ic_play_white)
                .setContentIntent(openPending)
                .addAction(0, getString(R.string.shuffle_notification_stop), stopPending)
                .setOngoing(true)
                .build();
    }
}