# Spotify Bar (Fabric, Minecraft 1.21.4) - free version

Client-side mod, Windows only. Shows the song you're playing in the Spotify
desktop app (title, artist, playing/paused) as a card on your screen that you
can drag anywhere. Works with Free and Premium. No login, no Spotify developer
account, nothing leaves your PC.

## Use

- Press **J** (rebindable in Controls) to open the Spotify Bar screen.
- Drag the card to move it. Position is saved.
- Pick the accent and background color with the swatches in that screen.
- Buttons under the card: previous / play-pause / next (they send the Windows
  media keys, so they work like the keys on your keyboard).
- Optional keybind (unbound by default) to hide/show the card.

## Notes

- Needs the Spotify **desktop app** (not the web player). The song is read from
  the app's window title, which shows "Artist - Song" while playing.
- No cover art or progress bar (that needs the Spotify API, which now requires Premium).

## Build

Push to GitHub; the Actions workflow builds the jar (artifact `spotify-bar-jar`).
Needs Fabric API for 1.21.4.
