package com.example.spotifybar;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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

	// separate thread for web lookups so they never delay the window polling
	private static final ScheduledExecutorService NET = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "SpotifyBar-Net");
		t.setDaemon(true);
		return t;
	});

	public static final int ART_SIZE = 64;
	private static final Identifier ART_ID = Identifier.of("spotifybar", "cover");

	/** Cover texture once loaded (null while loading / not found). */
	public volatile Identifier coverTexture = null;
	public volatile boolean hasCoverColors = false;
	public volatile int coverAccent = 0x1DB954;
	public volatile int coverBg = 0x101010;
	private volatile int generation = 0;
	private String lastKey = "";

	private static final boolean WINDOWS =System.getProperty("os.name", "").toLowerCase().contains("win");

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

		String key = artist + "\n" + title;
		if (!key.equals(lastKey)) {
			lastKey = key;
			int gen = ++generation;
			coverTexture = null;
			hasCoverColors = false;
			final String a = artist;
			final String s = title;
			NET.execute(() -> loadCover(a, s, gen));
		}
	}

	// ------------------------------------------------------------------ cover

	/** Finds the cover via Apple's free iTunes Search API and extracts its colors. */
	private void loadCover(String artistName, String songTitle, int gen) {
		try {
			String firstArtist = artistName.split(",|&| feat\\.", 2)[0].trim();
			String cleanTitle = songTitle
					.replaceAll("\\s*[\\(\\[].*?[\\)\\]]", "")
					.replaceAll("\\s-\\s.*$", "")
					.trim();
			String term = URLEncoder.encode((firstArtist + " " + cleanTitle).trim(), StandardCharsets.UTF_8);
			String json = httpGet("https://itunes.apple.com/search?media=music&entity=song&limit=1&term=" + term);
			JsonArray results = JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("results");
			if (results == null || results.isEmpty()) return;
			JsonObject first = results.get(0).getAsJsonObject();
			if (!first.has("artworkUrl100")) return;
			String url = first.get("artworkUrl100").getAsString()
					.replace("100x100bb.jpg", ART_SIZE + "x" + ART_SIZE + "bb.png");

			byte[] data = httpGetBytes(url);
			NativeImage img = NativeImage.read(new ByteArrayInputStream(data));
			int[] colors = extractColors(img);

			MinecraftClient mc = MinecraftClient.getInstance();
			mc.execute(() -> {
				if (gen != generation) { // song changed while we were loading
					img.close();
					return;
				}
				mc.getTextureManager().destroyTexture(ART_ID);
				mc.getTextureManager().registerTexture(ART_ID, new NativeImageBackedTexture(img));
				coverTexture = ART_ID;
				coverAccent = colors[0];
				coverBg = colors[1];
				hasCoverColors = true;
			});
		} catch (Throwable t) {
			SpotifyBarClient.LOGGER.warn("Could not load cover for {} - {}", artistName, songTitle, t);
		}
	}

	/** Returns {accent, background} RGB derived from the most colorful parts of the image. */
	private static int[] extractColors(NativeImage img) {
		double rs = 0, gs = 0, bs = 0, ws = 0;
		double ra = 0, ga = 0, ba = 0;
		int n = 0;
		for (int y = 0; y < img.getHeight(); y += 2) {
			for (int x = 0; x < img.getWidth(); x += 2) {
				int argb = img.getColorArgb(x, y);
				if ((argb >>> 24) < 128) continue;
				int r = (argb >> 16) & 255, g = (argb >> 8) & 255, b = argb & 255;
				int max = Math.max(r, Math.max(g, b));
				int min = Math.min(r, Math.min(g, b));
				double sat = max == 0 ? 0 : (max - min) / (double) max;
				double w = sat * sat * (0.3 + max / 255.0);
				rs += r * w; gs += g * w; bs += b * w; ws += w;
				ra += r; ga += g; ba += b; n++;
			}
		}
		double r, g, b;
		if (ws > 1e-3) {
			r = rs / ws; g = gs / ws; b = bs / ws;
		} else if (n > 0) { // grayscale cover
			r = ra / n; g = ga / n; b = ba / n;
		} else {
			return new int[]{0x1DB954, 0x101010};
		}
		float[] hsv = rgbToHsv(r, g, b);
		float accentS = hsv[1] < 0.12f ? hsv[1] : Math.max(0.5f, Math.min(1f, hsv[1]));
		int accent = hsvToRgb(hsv[0], accentS, Math.max(0.75f, hsv[2]));
		int bg = hsvToRgb(hsv[0], hsv[1] < 0.12f ? hsv[1] : Math.min(0.75f, Math.max(0.35f, hsv[1])), 0.2f);
		return new int[]{accent, bg};
	}

	private static float[] rgbToHsv(double r, double g, double b) {
		r /= 255; g /= 255; b /= 255;
		double max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
		double d = max - min, h;
		if (d == 0) h = 0;
		else if (max == r) h = ((g - b) / d) % 6;
		else if (max == g) h = (b - r) / d + 2;
		else h = (r - g) / d + 4;
		h /= 6;
		if (h < 0) h += 1;
		return new float[]{(float) h, (float) (max == 0 ? 0 : d / max), (float) max};
	}

	private static int hsvToRgb(float h, float s, float v) {
		float c = v * s;
		float hp = (h * 6f) % 6f;
		float x = c * (1 - Math.abs(hp % 2 - 1));
		float r1 = 0, g1 = 0, b1 = 0;
		if (hp < 1) { r1 = c; g1 = x; }
		else if (hp < 2) { r1 = x; g1 = c; }
		else if (hp < 3) { g1 = c; b1 = x; }
		else if (hp < 4) { g1 = x; b1 = c; }
		else if (hp < 5) { r1 = x; b1 = c; }
		else { r1 = c; b1 = x; }
		float m = v - c;
		int r = Math.round((r1 + m) * 255), g = Math.round((g1 + m) * 255), b = Math.round((b1 + m) * 255);
		return (r << 16) | (g << 8) | b;
	}

	private static String httpGet(String url) throws Exception {
		return new String(httpGetBytes(url), StandardCharsets.UTF_8);
	}

	private static byte[] httpGetBytes(String url) throws Exception {
		HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
		c.setConnectTimeout(8000);
		c.setReadTimeout(8000);
		c.setRequestProperty("User-Agent", "SpotifyBarMod/1.3");
		try (InputStream in = c.getInputStream()) {
			return in.readAllBytes();
		}
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
