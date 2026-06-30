package com.myalbumdj.app.android;

import android.app.Application;

import com.spotify.sdk.android.auth.AuthorizationClient;

/**
 * Clears the stored Spotify login on every cold start, so quitting or having the OS kill the app
 * effectively logs the user out: the next launch always begins logged out.
 *
 * Android does not guarantee any code runs when the process is killed, so clearing at the next
 * start is the dependable way to ensure a quit/kill leaves no logged-in session behind. (This runs
 * once per process, before any activity, so it only clears a previous session — not the current one.)
 */
public class MyAlbumDjApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        getSharedPreferences("spotify_auth", MODE_PRIVATE).edit().clear().apply();
        AuthorizationClient.clearCookies(this);
    }
}
