# Spotify Bar (Fabric, Minecraft 1.21.4) - free version

Client-side mod, Windows only. Shows the song you're playing in the Spotify
desktop app (title, artist, playing/paused) as a card on your screen that you
can drag anywhere. Works with Free and Premium. No login, no Spotify developer
account, nothing leaves your PC.

## Use

- Press **J** (rebindable in Controls) to open the Spotify Bar screen.
- Drag the card to move it. Position is saved.
- The card shows the song's cover and (by default) matches its colors to it. Turn that off with the
  "Match cover colors" button, or just click a swatch to pick your own accent/background.
- The text color has its own swatch row.
- Buttons under the card: previous / play-pause / next (they send the Windows
  media keys, so they work like the keys on your keyboard).
- Optional keybind (unbound by default) to hide/show the card.

## Notes

- Needs the Spotify **desktop app** (not the web player). The song is read from
  the app's window title, which shows "Artist - Song" while playing.
- The cover is found by song + artist name in Apple's free iTunes Search API (no key needed), so it
  can occasionally be the wrong release, or missing for obscure tracks. No progress bar.

## Build

Push to GitHub; the Actions workflow builds the jar (artifact `spotify-bar-jar`).
Needs Fabric API for 1.21.4.
