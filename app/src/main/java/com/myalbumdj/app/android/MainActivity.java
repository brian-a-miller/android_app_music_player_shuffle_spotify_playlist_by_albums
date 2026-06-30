package com.myalbumdj.app.android;

/*
 * Author: Claude (Anthropic)
 * Last modified: 2026-06-29
 * Version log:
 *   2026-06-29 - Route the login check and logout through SpotifyAuth so all token keys (including
 *                the new scope/expiry) live in one place and are cleared together on logout.
 */

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.spotify.sdk.android.auth.AuthorizationClient;

public class MainActivity extends AppCompatActivity {

    private Button loginToSpotifyButton;
    private Button listPlaylistsButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        loginToSpotifyButton = findViewById(R.id.loginToSpotifyButton);
        listPlaylistsButton = findViewById(R.id.listPlaylistsButton);

        // The first button toggles between logging in and logging out based on current state.
        loginToSpotifyButton.setOnClickListener(view -> {
            if (isLoggedIn()) {
                logOut();
            } else {
                startActivity(new Intent(this, SpotifyLoginActivity.class));
            }
        });

        listPlaylistsButton.setOnClickListener(view ->
                startActivity(new Intent(this, ListSpotifyPlaylists.class)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Reflect the current login state whenever we return to this screen (e.g. after login).
        updateButtons();
    }

    private void updateButtons() {
        boolean loggedIn = isLoggedIn();
        loginToSpotifyButton.setText(
                loggedIn ? R.string.log_out_of_spotify : R.string.log_in_to_spotify);
        listPlaylistsButton.setEnabled(loggedIn);
    }

    private boolean isLoggedIn() {
        return new SpotifyAuth(this).isLoggedIn();
    }

    private void logOut() {
        new SpotifyAuth(this).clear();
        AuthorizationClient.clearCookies(this);
        PlaylistCache.clear();
        SpotifyRemote.clear();
        updateButtons();
    }
}
