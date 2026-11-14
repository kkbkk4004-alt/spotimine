package com.example.spotifybar;

import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Free, no-login version: reads "Artist - Song" from the title of the Spotify
 * desktop app's window (Windows only) and controls playback with the system
 * media keys.
 */
public class SpotifyClient {
	private static final ScheduledExecutorService POOL = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "SpotifyBar");
		t.setDaemon(true);
		return t;
	});

	private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");

	// ---- state read by the UI ----
	/** Is the Spotify desktop app running? */
	public volatile boolean running = false;
	public volatile boolean hasTrack = false;
	public volatile boolean playing = false;
	public volatile String title = "";
	public volatile String artist = "";
	public volatile String status = "";

	public void start() {
		if (!WINDOWS) {
			status = "Windows only";
			return;
		}
		POOL.scheduleWithFixedDelay(() -> {
			try {
				poll();
			} catch (Throwable t) {
				status = "Could not read Spotify window";
				SpotifyBarClient.LOGGER.debug("poll failed", t);
			}
		}, 1, 1, TimeUnit.SECONDS);
	}

	private void poll() {
		String raw = findSpotifyTitle();
		if (raw == null) {
			running = false;
			playing = false;
			hasTrack = false;
			return;
		}
		running = true;
		status = "";
		String t = raw.trim();

		boolean generic = t.equalsIgnoreCase("Spotify")
				|| t.matches("(?i)spotify (free|premium|standalone).*")
				|| t.equalsIgnoreCase("Advertisement");
		if (generic) { // paused, stopped or an ad: keep the last song on screen
			playing = false;
			return;
		}

		int sep = t.indexOf(" - ");
		if (sep > 0) {
			artist = t.substring(0, sep);
			title = t.substring(sep + 3);
		} else {
			artist = "";
			title = t;
		}
		hasTrack = true;
		playing = true;
	}

	/** Title of Spotify's main window, or null if Spotify isn't running. */
	private static String findSpotifyTitle() {
		final String[] best = {null};
		final String[] fallback = {null};

		User32.INSTANCE.EnumWindows(new User32.EnumProc() {
			@Override
			public boolean callback(Pointer hWnd, Pointer data) {
				char[] buf = new char[512];
				int len = User32.INSTANCE.GetWindowTextW(hWnd, buf, 512);
				if (len <= 0) return true;
				String text = new String(buf, 0, len);
				if (text.startsWith("GDI+") || text.startsWith("Default IME")
						|| text.startsWith("MSCTFIME") || text.startsWith("Chrome Legacy")
						|| text.startsWith("Spotify Widget")) {
					return true;
				}

				IntByReference pid = new IntByReference();
				User32.INSTANCE.GetWindowThreadProcessId(hWnd, pid);
				boolean isSpotify = ProcessHandle.of(pid.getValue())
						.flatMap(p -> p.info().command())
						.map(c -> c.toLowerCase().endsWith("spotify.exe"))
						.orElse(false);
				if (!isSpotify) return true;

				if (text.contains(" - ")) {
					best[0] = text;
					return false; // found a song title, stop
				}
				if (fallback[0] == null && text.toLowerCase().startsWith("spotify")) {
					fallback[0] = text;
				}
				return true;
			}
		}, null);

		return best[0] != null ? best[0] : fallback[0];
	}

	// --------------------------------------------------------------- controls

	public void togglePlay() {
		mediaKey(0xB3); // VK_MEDIA_PLAY_PAUSE
		playing = !playing;
	}

	public void next() {
		mediaKey(0xB0); // VK_MEDIA_NEXT_TRACK
	}

	public void previous() {
		mediaKey(0xB1); // VK_MEDIA_PREV_TRACK
	}

	private void mediaKey(int vk) {
		if (!WINDOWS) return;
		POOL.execute(() -> {
			try {
				User32.INSTANCE.keybd_event((byte) vk, (byte) 0, 0, null);
				User32.INSTANCE.keybd_event((byte) vk, (byte) 0, 2, null); // key up
			} catch (Throwable t) {
				SpotifyBarClient.LOGGER.warn("Media key failed", t);
			}
		});
	}
}
