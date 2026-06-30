package com.myalbumdj.app.android;

/*
 * Author: Claude (Anthropic)
 * Last modified: 2026-06-29
 * Version log:
 *   2026-06-29 - New: central store for the Spotify OAuth tokens plus silent access-token refresh
 *                using the stored refresh_token, so an expired access token no longer forces the
 *                user to log in again. Consolidates the "spotify_auth" SharedPreferences keys that
 *                were previously duplicated across the activities.
 */

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Single source of truth for the user's Spotify login state.
 *
 * <p>Holds the access/refresh tokens (in the {@code spotify_auth} SharedPreferences) and refreshes
 * the short-lived access token on demand using the long-lived refresh token. The Spotify access
 * token expires after about an hour; without a refresh the user would be forced to log in again.
 *
 * <p>Refreshing makes a network call, so {@link #getValidAccessToken()} and {@link #refresh()} must
 * be called off the main thread (e.g. from the background executors the activities already use).
 */
final class SpotifyAuth {

    private static final String TOKEN_ENDPOINT = "https://accounts.spotify.com/api/token";

    private static final String PREFS = "spotify_auth";
    private static final String KEY_ACCESS_TOKEN = "access_token";
    private static final String KEY_REFRESH_TOKEN = "refresh_token";
    private static final String KEY_SCOPE = "scope";
    // Wall-clock time (System.currentTimeMillis) at which the access token stops being valid.
    private static final String KEY_EXPIRES_AT = "expires_at";

    // Refresh a little before the real expiry so a request never goes out with a just-expired token.
    private static final long EXPIRY_MARGIN_MS = 60_000L;

    private final Context context;

    /** Uses the application context so the stored tokens are the same regardless of which screen asks. */
    SpotifyAuth(Context context) {
        this.context = context.getApplicationContext();
    }

    /** True once we have an access token (i.e. the user has logged in this session). */
    boolean isLoggedIn() {
        return !TextUtils.isEmpty(prefs().getString(KEY_ACCESS_TOKEN, null));
    }

    /** The currently-stored access token, without checking whether it has expired. */
    String getAccessToken() {
        return prefs().getString(KEY_ACCESS_TOKEN, null);
    }

    /**
     * Persists the tokens from a token-endpoint response (login or refresh). A refresh response may
     * omit {@code refresh_token}, in which case we keep the existing one. Always updates the access
     * token, granted scopes, and the computed expiry.
     */
    void saveTokens(JSONObject tokenResponse) {
        SharedPreferences.Editor edit = prefs().edit();
        edit.putString(KEY_ACCESS_TOKEN, tokenResponse.optString("access_token", null));
        // Spotify returns expires_in in seconds (typically 3600).
        long expiresInMs = tokenResponse.optLong("expires_in", 3600L) * 1000L;
        edit.putLong(KEY_EXPIRES_AT, System.currentTimeMillis() + expiresInMs);
        if (tokenResponse.has("scope")) {
            edit.putString(KEY_SCOPE, tokenResponse.optString("scope", ""));
        }
        String refreshToken = tokenResponse.optString("refresh_token", null);
        if (!TextUtils.isEmpty(refreshToken)) {
            edit.putString(KEY_REFRESH_TOKEN, refreshToken);
        }
        edit.apply();
    }

    /**
     * Returns an access token that is valid right now, refreshing first if the stored one has (or is
     * about to) expire. Call off the main thread.
     */
    String getValidAccessToken() throws IOException {
        long expiresAt = prefs().getLong(KEY_EXPIRES_AT, 0L);
        if (System.currentTimeMillis() >= expiresAt - EXPIRY_MARGIN_MS) {
            return refresh();
        }
        return getAccessToken();
    }

    /**
     * Exchanges the stored refresh token for a fresh access token and saves it. Returns the new
     * access token. Throws if there is no refresh token or the exchange fails (e.g. the refresh
     * token was revoked) — the caller should treat that as "must log in again". Call off the main
     * thread.
     */
    String refresh() throws IOException {
        String refreshToken = prefs().getString(KEY_REFRESH_TOKEN, null);
        if (TextUtils.isEmpty(refreshToken)) {
            throw new IOException("no refresh token; user must log in again");
        }
        String body = "grant_type=refresh_token"
                + "&refresh_token=" + enc(refreshToken)
                + "&client_id=" + enc(BuildConfig.SPOTIFY_CLIENT_ID);
        JSONObject json = postForm(body);
        String accessToken = json.optString("access_token", null);
        if (TextUtils.isEmpty(accessToken)) {
            String error = json.optString("error_description", json.optString("error", "refresh failed"));
            throw new IOException("token refresh failed: " + error);
        }
        saveTokens(json);
        return accessToken;
    }

    /** Forgets all stored tokens (logout). */
    void clear() {
        prefs().edit()
                .remove(KEY_ACCESS_TOKEN)
                .remove(KEY_REFRESH_TOKEN)
                .remove(KEY_SCOPE)
                .remove(KEY_EXPIRES_AT)
                .apply();
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // --- HTTP / encoding helpers ---

    private static String enc(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static JSONObject postForm(String formBody) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(TOKEN_ENDPOINT).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(formBody.getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String body = readAll(is);
            try {
                return new JSONObject(body);
            } catch (Exception e) {
                throw new IOException("HTTP " + code + ": " + body);
            }
        } finally {
            conn.disconnect();
        }
    }

    private static String readAll(InputStream is) throws IOException {
        if (is == null) {
            return "";
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) != -1) {
            bos.write(buf, 0, n);
        }
        return bos.toString("UTF-8");
    }
}