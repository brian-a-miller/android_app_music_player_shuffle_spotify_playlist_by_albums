package com.myalbumdj.app.android;

/*
 * Author: Claude (Anthropic)
 * Last modified: 2026-06-29
 * Version log:
 *   2026-06-29 - On successful token exchange, persist the tokens via SpotifyAuth.saveTokens (which
 *                also records the access-token expiry used for silent refresh). This activity now
 *                only clears its own PKCE/CSRF markers.
 */

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.spotify.sdk.android.auth.AuthorizationClient;
import com.spotify.sdk.android.auth.AuthorizationRequest;
import com.spotify.sdk.android.auth.AuthorizationResponse;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Drives the Spotify login flow using the Authorization Code flow with PKCE, over the Auth SDK's
 * browser-based flow.
 *
 * Why this combination:
 *  - Browser (Custom Tab) instead of the SDK's WebView, so third-party identity providers
 *    (Google/Facebook) can complete their own OAuth redirects.
 *  - Authorization Code + PKCE instead of the implicit TOKEN flow, because Spotify has deprecated
 *    implicit grant (the token flow now fails with "response_type must be code"). PKCE lets a
 *    public mobile client exchange the code for tokens WITHOUT a client secret on-device.
 *
 * Spotify redirects back to {@code SPOTIFY_REDIRECT_URI}, caught via the intent-filter in
 * AndroidManifest.xml and delivered to {@link #onNewIntent} (or {@link #onCreate} after a process
 * death). The PKCE verifier and CSRF state are persisted so the round-trip survives that.
 */
public class SpotifyLoginActivity extends AppCompatActivity {

    private static final String TOKEN_ENDPOINT = "https://accounts.spotify.com/api/token";
    private static final String PREFS = "spotify_auth";
    private static final String KEY_CODE_VERIFIER = "code_verifier";
    private static final String KEY_STATE = "state";

    private static final String TAG = "SpotifyLogin";

    private static final String[] SCOPES = new String[]{
            "user-read-email",
            "user-read-private",
            "streaming",
            "playlist-read-private",
            "user-library-read",
            // Required for the App Remote SDK to control playback in the Spotify app.
            "app-remote-control"
    };

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private TextView statusView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_spotify_login);
        statusView = findViewById(R.id.loginStatusTextView);

        if (TextUtils.isEmpty(BuildConfig.SPOTIFY_CLIENT_ID)) {
            statusView.setText(R.string.login_not_configured);
            return;
        }

        // If we were (re)started directly by the redirect deep link, handle it; otherwise begin
        // login. The savedInstanceState guard avoids relaunching the browser on recreation while
        // the Custom Tab is still in front of us.
        if (getIntent() != null && getIntent().getData() != null) {
            handleRedirect(getIntent());
        } else if (savedInstanceState == null) {
            startSpotifyLogin();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleRedirect(intent);
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private void startSpotifyLogin() {
        String codeVerifier = generateCodeVerifier();
        String state = generateState();
        prefs().edit()
                .putString(KEY_CODE_VERIFIER, codeVerifier)
                .putString(KEY_STATE, state)
                .apply();

        AuthorizationRequest request = new AuthorizationRequest.Builder(
                BuildConfig.SPOTIFY_CLIENT_ID,
                AuthorizationResponse.Type.CODE,
                BuildConfig.SPOTIFY_REDIRECT_URI)
                .setScopes(SCOPES)
                .setState(state)
                // Force the consent screen so newly-added scopes are actually presented/granted.
                .setShowDialog(true)
                .setCustomParam("code_challenge_method", "S256")
                .setCustomParam("code_challenge", codeChallenge(codeVerifier))
                .build();

        AuthorizationClient.openLoginInBrowser(this, request);
    }

    private void handleRedirect(Intent intent) {
        Uri uri = intent.getData();
        if (uri == null) {
            return;
        }

        String pendingState = prefs().getString(KEY_STATE, null);
        if (pendingState == null) {
            // No login is in progress, so this redirect isn't ours (e.g. App Remote's auth flow,
            // or a stale re-delivery of an already-handled redirect). Ignore it.
            finish();
            return;
        }

        AuthorizationResponse response = AuthorizationResponse.fromUri(uri);
        switch (response.getType()) {
            case CODE:
                if (!TextUtils.equals(response.getState(), pendingState)) {
                    statusView.setText(getString(R.string.login_error, "state mismatch"));
                    return;
                }
                statusView.setText(R.string.login_in_progress);
                exchangeCodeForToken(response.getCode());
                break;
            case ERROR:
                statusView.setText(getString(R.string.login_error, response.getError()));
                break;
            default:
                // The user cancelled or dismissed the auth flow.
                statusView.setText(R.string.login_cancelled);
                break;
        }
    }

    /** Exchanges the authorization code for tokens via PKCE (no client secret required). */
    private void exchangeCodeForToken(String code) {
        String codeVerifier = prefs().getString(KEY_CODE_VERIFIER, null);
        if (TextUtils.isEmpty(codeVerifier)) {
            statusView.setText(getString(R.string.login_error, "missing PKCE verifier"));
            return;
        }

        executor.execute(() -> {
            try {
                String body = "grant_type=authorization_code"
                        + "&code=" + enc(code)
                        + "&redirect_uri=" + enc(BuildConfig.SPOTIFY_REDIRECT_URI)
                        + "&client_id=" + enc(BuildConfig.SPOTIFY_CLIENT_ID)
                        + "&code_verifier=" + enc(codeVerifier);

                JSONObject json = postForm(TOKEN_ENDPOINT, body);
                String accessToken = json.optString("access_token", null);
                if (!TextUtils.isEmpty(accessToken)) {
                    Log.d(TAG, "Granted scopes: " + json.optString("scope", ""));
                    // Persist access/refresh tokens, scopes, and the computed expiry in one place.
                    new SpotifyAuth(this).saveTokens(json);
                    prefs().edit()
                            .remove(KEY_CODE_VERIFIER)
                            // Clear the pending-login marker so later redirects (e.g. App Remote's
                            // auth flow) are ignored by handleRedirect.
                            .remove(KEY_STATE)
                            .apply();
                    onLoginSuccess();
                } else {
                    String error = json.optString("error_description",
                            json.optString("error", "token exchange failed"));
                    showStatus(getString(R.string.login_error, error));
                }
            } catch (Exception e) {
                showStatus(getString(R.string.login_error, String.valueOf(e.getMessage())));
            }
        });
    }

    private void showStatus(String text) {
        mainHandler.post(() -> statusView.setText(text));
    }

    private void onLoginSuccess() {
        mainHandler.post(() -> {
            statusView.setText(R.string.login_success);
            // Return to MainActivity, which switches to its logged-in button state.
            finish();
        });
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    // --- PKCE helpers ---

    private static String generateCodeVerifier() {
        byte[] bytes = new byte[64];
        new SecureRandom().nextBytes(bytes);
        return base64Url(bytes);
    }

    private static String generateState() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        return base64Url(bytes);
    }

    private static String codeChallenge(String codeVerifier) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
            return base64Url(hash);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String base64Url(byte[] bytes) {
        return Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
    }

    // --- HTTP helpers ---

    private static String enc(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static JSONObject postForm(String endpoint, String formBody) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
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
