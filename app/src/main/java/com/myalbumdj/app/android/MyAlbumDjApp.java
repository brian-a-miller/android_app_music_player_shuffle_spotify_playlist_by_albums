package com.myalbumdj.app.android;

/*
 * Author: Claude (Anthropic)
 * Last modified: 2026-06-30
 * Version log:
 *   2026-06-30 - Fix #3: stopped wiping the stored Spotify login on every cold start. Login now
 *                persists across app restarts and OS process kills (an expired access token is
 *                silently refreshed via SpotifyAuth), so returning to the app after time away no
 *                longer forces a re-login. Logout is explicit-only, via MainActivity's Log out button.
 */

import android.app.Application;

/**
 * Application entry point. Intentionally does no auth clearing: the Spotify login is meant to
 * persist across cold starts and OS process kills. Logging out happens only when the user taps
 * "Log out" (see {@link MainActivity#logOut()}), which clears the tokens via {@link SpotifyAuth}
 * and Spotify's auth cookies.
 *
 * Kept as the registered Application class (see AndroidManifest) so there's a home for any future
 * app-wide initialization.
 */
public class MyAlbumDjApp extends Application {
}