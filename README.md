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
- Colors: pick Accent / Background / Text with the tabs, then click one of 48 palette colors or type any hex color (like #FF8800).
- A soft glow in the cover's color pulses behind the cover while music plays (toggle with the "Glow" button).
- Buttons under the card: previous / play-pause / next (they send the Windows
  media keys, so they work like the keys on your keyboard).
- Optional keybind (unbound by default) to hide/show the card.

- Time bar: elapsed time, time left and total length. Click the bar in the J screen to correct the time.

- Card shape: Square, Rounded, Pill or Cut corners (button in the J screen).

## Notes

- Needs the Spotify **desktop app** (not the web player). The song is read from
  the app's window title, which shows "Artist - Song" while playing.
- The cover is found by song + artist name in Deezer's free search API (Apple's iTunes Search is the
  backup). A result is only used if its artist and title match, so a missing cover is preferred over a
  wrong one. No keys or accounts needed. No progress bar.

## Build

Push to GitHub; the Actions workflow builds the jar (artifact `spotify-bar-jar`).
Needs Fabric API for 1.21.4.

- The song length comes from Deezer/iTunes. Spotify doesn't share its playback position with free mods,
  so the elapsed time is counted by the mod from when it sees the song start. If you start the game
  mid-song or seek in Spotify, click the bar in the J screen to re-sync.
