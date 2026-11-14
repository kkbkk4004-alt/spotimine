# Spotify Bar (Fabric, Minecraft 1.21.4)

Client-side mod. Shows the song you're playing on Spotify (cover, title, artist,
progress) as a card on your screen that you can drag anywhere. Controls
(previous / play-pause / next) are in the edit screen.

## Use

- Press **J** (rebindable in Controls) to open the Spotify Bar screen.
- Drag the card to move it. Position is saved.
- In that screen: paste your Client ID, click **Log in with Spotify**, use the
  controls under the card.
- Optional keybind (unbound by default) to hide/show the card.

## One-time Spotify setup

1. Go to https://developer.spotify.com/dashboard and log in.
2. **Create app**. Name/description: anything. Redirect URI: exactly
   `http://127.0.0.1:8888/callback`. Tick **Web API**. Save.
3. Open the app's **Settings** and copy the **Client ID**.
4. In Minecraft press J, paste the Client ID, click **Log in with Spotify**,
   approve in the browser. You stay logged in on later launches.

Notes:
- Since 2026 Spotify requires Premium for apps in Development Mode (the account
  that owns the app), and limits each app to 5 users.
- Play/pause/skip buttons also need Premium.
- The login token is stored in `.minecraft/config/spotifybar.json`. Don't share it.

## Build

Push to GitHub; the Actions workflow builds the jar (artifact `spotify-bar-jar`).
Locally: JDK 21, then `gradle build`. Needs Fabric API for 1.21.4.
