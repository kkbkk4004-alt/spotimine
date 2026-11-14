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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Free, no-login version: reads "Artist - Song" from the title of the Spotify
 * desktop app's window (Windows only), controls playback with the system media
 * keys and looks up the cover art on Deezer / iTunes.
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
	private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");

	/** A finished cover: 64x64 pixels plus the colors taken from it. */
	private record Cover(int[] argb, int accent, int bg) {}

	// ---- state read by the UI ----
	/** Is the Spotify desktop app running? */
	public volatile boolean running = false;
	public volatile boolean hasTrack = false;
	public volatile boolean playing = false;
	public volatile String title = "";
	public volatile String artist = "";
	public volatile String status = "";

	/** Cover texture once loaded (null while loading / not found). */
	public volatile Identifier coverTexture = null;
	public volatile boolean hasCoverColors = false;
	public volatile int coverAccent = 0x1DB954;
	public volatile int coverBg = 0x101010;

	private volatile int generation = 0;
	private String lastKey = "";

	/** Remembers the last 24 covers so replaying a song needs no internet. */
	private final Map<String, Cover> coverCache = new LinkedHashMap<>(32, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, Cover> eldest) {
			return size() > 24;
		}
	};

	/** pid -> "is this Spotify.exe?" so we don't ask Windows about every window every second. */
	private final Map<Integer, Boolean> pidCache = new HashMap<>();

	public void start() {
		if (!WINDOWS) {
			status = "Windows only";
			return;
		}
		POOL.scheduleWithFixedDelay(() -> {
			try {
				if (!shouldPoll()) return; // card hidden: do no work at all
				poll();
			} catch (Throwable t) {
				status = "Could not read Spotify window";
				SpotifyBarClient.LOGGER.debug("poll failed", t);
			}
		}, 1, 1, TimeUnit.SECONDS);
	}

	private static boolean shouldPoll() {
		return Config.get().visible || MinecraftClient.getInstance().currentScreen instanceof SpotifyScreen;
	}

	private void poll() {
		String raw = findSpotifyTitle();
		if (raw == null) {
			running = false;
			playing = false;
			hasTrack = false;
			pidCache.clear();
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

		String newArtist, newTitle;
		int sep = t.indexOf(" - ");
		if (sep > 0) {
			newArtist = t.substring(0, sep);
			newTitle = t.substring(sep + 3);
		} else {
			newArtist = "";
			newTitle = t;
		}
		artist = newArtist;
		title = newTitle;
		hasTrack = true;
		playing = true;

		String key = newArtist + "\n" + newTitle;
		if (!key.equals(lastKey)) {
			lastKey = key;
			int gen = ++generation;
			coverTexture = null;
			hasCoverColors = false;
			NET.execute(() -> loadCover(newArtist, newTitle, gen));
		}
	}

	/** Title of Spotify's main window, or null if Spotify isn't running. */
	private String findSpotifyTitle() {
		final String[] best = {null};
		final String[] fallback = {null};

		User32.INSTANCE.EnumWindows(new User32.EnumProc() {
			@Override
			public boolean callback(Pointer hWnd, Pointer data) {
				char[] buf = new char[512];
				int len = User32.INSTANCE.GetWindowTextW(hWnd, buf, 512);
				if (len <= 0) return true;
				String text = new String(buf, 0, len);

				// cheap string checks first: only these can be Spotify's main window
				boolean song = text.contains(" - ");
				boolean generic = text.regionMatches(true, 0, "spotify", 0, 7);
				if (!song && !generic) return true;
				if (text.startsWith("GDI+") || text.startsWith("Default IME")
						|| text.startsWith("MSCTFIME") || text.startsWith("Chrome Legacy")
						|| text.startsWith("Spotify Widget")) {
					return true;
				}
				if (!song && fallback[0] != null) return true;

				IntByReference pid = new IntByReference();
				User32.INSTANCE.GetWindowThreadProcessId(hWnd, pid);
				boolean isSpotify = pidCache.computeIfAbsent(pid.getValue(), p ->
						ProcessHandle.of(p)
								.flatMap(h -> h.info().command())
								.map(c -> c.toLowerCase().endsWith("spotify.exe"))
								.orElse(false));
				if (!isSpotify) return true;

				if (song) {
					best[0] = text;
					return false; // found a song title, stop
				}
				fallback[0] = text;
				return true;
			}
		}, null);

		return best[0] != null ? best[0] : fallback[0];
	}

	// ------------------------------------------------------------------ cover

	private void loadCover(String artistName, String songTitle, int gen) {
		try {
			String key = artistName + "\n" + songTitle;
			Cover cover;
			synchronized (coverCache) {
				cover = coverCache.get(key);
			}
			if (cover == null) {
				cover = fetchCover(artistName, songTitle);
				if (cover == null) return;
				synchronized (coverCache) {
					coverCache.put(key, cover);
				}
			}
			applyCover(cover, gen);
		} catch (Throwable t) {
			SpotifyBarClient.LOGGER.warn("Could not load cover for {} - {}", artistName, songTitle, t);
		}
	}

	/** Uploads the cover to the GPU on the render thread (unless the song already changed). */
	private void applyCover(Cover cover, int gen) {
		MinecraftClient mc = MinecraftClient.getInstance();
		mc.execute(() -> {
			if (gen != generation) return;
			NativeImage img = new NativeImage(ART_SIZE, ART_SIZE, false);
			for (int y = 0; y < ART_SIZE; y++) {
				for (int x = 0; x < ART_SIZE; x++) img.setColorArgb(x, y, cover.argb()[y * ART_SIZE + x]);
			}
			mc.getTextureManager().destroyTexture(ART_ID);
			mc.getTextureManager().registerTexture(ART_ID, new NativeImageBackedTexture(img));
			coverTexture = ART_ID;
			coverAccent = cover.accent();
			coverBg = cover.bg();
			hasCoverColors = true;
		});
	}

	/** Finds the cover (Deezer first, iTunes as backup) and checks it is the same song. */
	private static Cover fetchCover(String artistName, String songTitle) throws Exception {
		String firstArtist = artistName.split(",|&| feat\\.", 2)[0].trim();
		String cleanTitle = songTitle
				.replaceAll("\\s*[\\(\\[].*?[\\)\\]]", "")
				.replaceAll("\\s-\\s.*$", "")
				.trim();
		String term = URLEncoder.encode((firstArtist + " " + cleanTitle).trim(), StandardCharsets.UTF_8);

		int[] px = null;
		try {
			String url = deezerCover(term, firstArtist, cleanTitle);
			if (url != null) px = toArt(httpGetBytes(url));
		} catch (Exception e) {
			SpotifyBarClient.LOGGER.debug("Deezer lookup failed", e);
		}
		if (px == null) {
			String url = itunesCover(term, firstArtist, cleanTitle);
			if (url != null) px = toArt(httpGetBytes(url));
		}
		if (px == null) return null;
		int[] colors = extractColors(px);
		return new Cover(px, colors[0], colors[1]);
	}

	private static String deezerCover(String term, String wantArtist, String wantTitle) throws Exception {
		String json = httpGet("https://api.deezer.com/search?limit=5&q=" + term);
		JsonArray data = JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("data");
		if (data == null) return null;
		for (var el : data) {
			JsonObject o = el.getAsJsonObject();
			String gotTitle = o.has("title") ? o.get("title").getAsString() : "";
			String gotArtist = o.has("artist") ? o.getAsJsonObject("artist").get("name").getAsString() : "";
			if (score(gotTitle, gotArtist, wantTitle, wantArtist) < 3) continue;
			JsonObject album = o.getAsJsonObject("album");
			if (album == null) continue;
			for (String key : new String[]{"cover_medium", "cover_big", "cover"}) {
				if (album.has(key) && !album.get(key).isJsonNull()) return album.get(key).getAsString();
			}
		}
		return null;
	}

	private static String itunesCover(String term, String wantArtist, String wantTitle) {
		try {
			String json = httpGet("https://itunes.apple.com/search?media=music&entity=song&limit=5&term=" + term);
			JsonArray results = JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("results");
			if (results == null) return null;
			for (var el : results) {
				JsonObject o = el.getAsJsonObject();
				String gotTitle = o.has("trackName") ? o.get("trackName").getAsString() : "";
				String gotArtist = o.has("artistName") ? o.get("artistName").getAsString() : "";
				if (score(gotTitle, gotArtist, wantTitle, wantArtist) < 3) continue;
				if (!o.has("artworkUrl100")) continue;
				return o.get("artworkUrl100").getAsString().replace("100x100bb.jpg", "200x200bb.jpg");
			}
		} catch (Exception e) {
			SpotifyBarClient.LOGGER.debug("iTunes lookup failed", e);
		}
		return null;
	}

	private static String norm(String s) {
		return s.toLowerCase().replaceAll("[^\\p{L}\\p{N}]", "");
	}

	/** 2 points for a matching artist, up to 2 for a matching title. 3+ means "same song". */
	private static int score(String gotTitle, String gotArtist, String wantTitle, String wantArtist) {
		String gt = norm(gotTitle), ga = norm(gotArtist), wt = norm(wantTitle), wa = norm(wantArtist);
		int score = 0;
		if (!wa.isEmpty() && (ga.contains(wa) || wa.contains(ga))) score += 2;
		if (gt.equals(wt)) score += 2;
		else if (!wt.isEmpty() && (gt.contains(wt) || wt.contains(gt))) score += 1;
		return score;
	}

	/** Decodes a JPEG/PNG and box-filters it down to a smooth 64x64 array of ARGB pixels. */
	private static int[] toArt(byte[] data) {
		int[] px = null;
		int w = 0, h = 0;
		try (NativeImage src = NativeImage.read(new ByteArrayInputStream(data))) {
			w = src.getWidth();
			h = src.getHeight();
			px = new int[w * h];
			for (int y = 0; y < h; y++) {
				for (int x = 0; x < w; x++) px[y * w + x] = src.getColorArgb(x, y);
			}
		} catch (Throwable t) {
			px = null; // not decodable by the game (e.g. JPEG): try ImageIO
		}
		if (px == null) {
			try {
				ImageFallback.Pixels p = ImageFallback.decode(data);
				if (p == null) return null;
				px = p.argb();
				w = p.width();
				h = p.height();
			} catch (Throwable t) {
				SpotifyBarClient.LOGGER.warn("Could not decode cover image", t);
				return null;
			}
		}

		int[] out = new int[ART_SIZE * ART_SIZE];
		for (int ty = 0; ty < ART_SIZE; ty++) {
			int y0 = ty * h / ART_SIZE, y1 = Math.max(y0 + 1, (ty + 1) * h / ART_SIZE);
			for (int tx = 0; tx < ART_SIZE; tx++) {
				int x0 = tx * w / ART_SIZE, x1 = Math.max(x0 + 1, (tx + 1) * w / ART_SIZE);
				long a = 0, r = 0, g = 0, b = 0;
				int n = 0;
				for (int y = y0; y < y1; y++) {
					for (int x = x0; x < x1; x++) {
						int c = px[y * w + x];
						a += c >>> 24;
						r += (c >> 16) & 255;
						g += (c >> 8) & 255;
						b += c & 255;
						n++;
					}
				}
				out[ty * ART_SIZE + tx] = (int) (a / n) << 24 | (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
			}
		}
		return out;
	}

	/** Returns {accent, background} RGB derived from the most colorful parts of the image. */
	private static int[] extractColors(int[] px) {
		double rs = 0, gs = 0, bs = 0, ws = 0;
		double ra = 0, ga = 0, ba = 0;
		int n = 0;
		for (int y = 0; y < ART_SIZE; y += 2) {
			for (int x = 0; x < ART_SIZE; x += 2) {
				int argb = px[y * ART_SIZE + x];
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

	public static int hsvToRgb(float h, float s, float v) {
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
		c.setRequestProperty("User-Agent", "SpotifyBarMod/1.7");
		try (InputStream in = c.getInputStream()) {
			return in.readAllBytes();
		}
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
