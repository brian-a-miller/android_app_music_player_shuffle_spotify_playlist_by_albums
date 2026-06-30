package com.myalbumdj.app.android;

/*
 * Author: Claude (Anthropic)
 * Last modified: 2026-06-29
 * Version log:
 *   2026-06-29 - getJson now takes a SpotifyAuth instead of a bare token string: it asks for a
 *                valid (auto-refreshed) access token, and if the API still answers 401 it forces a
 *                refresh and retries the request once. Replaces the old "throw on 401" TODO so an
 *                expired token no longer surfaces as an error to the user.
 */

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Minimal helper for authenticated GET requests against the Spotify Web API. */
final class SpotifyWebApi {

    static final String BASE_URL = "https://api.spotify.com/v1";

    private SpotifyWebApi() {
    }

    /**
     * Performs an authenticated GET and returns the parsed JSON body, transparently refreshing the
     * access token when it has expired. If the request comes back 401 even with a freshly-validated
     * token, we force one refresh and retry once before giving up. Call off the main thread (it may
     * make a token-refresh network call via {@link SpotifyAuth}).
     */
    static JSONObject getJson(String url, SpotifyAuth auth) throws IOException {
        Response response = request(url, auth.getValidAccessToken());
        if (response.code == 401) {
            // The token was rejected despite our expiry check (e.g. revoked, or our clock drifted).
            // Force a refresh and try the request one more time.
            response = request(url, auth.refresh());
        }
        if (response.code >= 400) {
            throw new IOException("HTTP " + response.code + ": " + response.body);
        }
        try {
            return new JSONObject(response.body);
        } catch (Exception e) {
            throw new IOException("Unexpected response: " + response.body);
        }
    }

    /** A single GET's status code and body, kept together so callers can react to the code. */
    private static final class Response {
        final int code;
        final String body;

        Response(int code, String body) {
            this.code = code;
            this.body = body;
        }
    }

    private static Response request(String url, String accessToken) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "Bearer " + accessToken);
            conn.setRequestProperty("Accept", "application/json");

            int code = conn.getResponseCode();
            InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            return new Response(code, readAll(is));
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
