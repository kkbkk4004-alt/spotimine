package com.example.spotifybar;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Talks to the Spotify Web API (login with PKCE, now-playing, play/pause/skip).
 * Everything network-related runs on background threads; the fields below are
 * read from the render thread.
 */
public class SpotifyClient {
	public static final int REDIRECT_PORT = 8888;
	public static final String REDIRECT_URI = "http://127.0.0.1:" + REDIRECT_PORT + "/callback";
	private static final String SCOPES = "user-read-currently-playing user-read-playback-state user-modify-playback-state";
	public static final int ART_SIZE = 64;
	private static final Identifier ART_ID = Identifier.of("spotifybar", "cover");

	private static final ScheduledExecutorService POOL = Executors.newScheduledThreadPool(2, r -> {
		Thread t = new Thread(r, "SpotifyBar");
		t.setDaemon(true);
		return t;
	});

	// ---- state read by the UI ----
	public volatile String status = "";
	public volatile boolean loggedIn = false;
	public volatile boolean loggingIn = false;
	public volatile boolean hasTrack = false;
	public volatile boolean playing = false;
	public volatile String title = "";
	public volatile String artist = "";
	public volatile long durationMs = 0;
	private volatile long progressAtFetchMs = 0;
	private volatile long fetchedAtNanos = 0;
	/** Non-null once the cover has been uploaded to the GPU. */
	public volatile Identifier artTexture = null;

	private volatile String accessToken = "";
	private volatile long accessExpiresAtMs = 0;
	private volatile String currentArtUrl = "";
	private volatile long backoffUntilMs = 0;

	public void start() {
		POOL.scheduleWithFixedDelay(this::pollSafe, 2, 3, TimeUnit.SECONDS);
		if (!Config.get().refreshToken.isEmpty() && !Config.get().clientId.isEmpty()) {
			POOL.execute(() -> {
				try {
					refresh();
				} catch (Exception e) {
					status = "Login expired - click Log in again";
					SpotifyBarClient.LOGGER.warn("Refresh failed", e);
				}
			});
		}
	}

	/** Progress in ms, smoothly advancing between polls. */
	public long progressMs() {
		if (!hasTrack) return 0;
		long p = progressAtFetchMs;
		if (playing) p += (System.nanoTime() - fetchedAtNanos) / 1_000_000L;
		return Math.max(0, Math.min(p, durationMs));
	}

	// ------------------------------------------------------------------ login

	public void login() {
		Config cfg = Config.get();
		if (cfg.clientId.isBlank()) {
			status = "Paste your Client ID first";
			return;
		}
		if (loggingIn) return;
		loggingIn = true;
		status = "Waiting for browser login...";
		POOL.execute(() -> {
			try {
				doLogin(cfg.clientId.trim());
			} catch (Exception e) {
				status = "Login failed: " + e.getMessage();
				SpotifyBarClient.LOGGER.warn("Login failed", e);
			} finally {
				loggingIn = false;
			}
		});
	}

	private void doLogin(String clientId) throws Exception {
		SecureRandom rnd = new SecureRandom();
		byte[] vb = new byte[48];
		rnd.nextBytes(vb);
		String verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(vb);
		String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
				MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
		byte[] sb = new byte[16];
		rnd.nextBytes(sb);
		String state = Base64.getUrlEncoder().withoutPadding().encodeToString(sb);

		String url = "https://accounts.spotify.com/authorize"
				+ "?client_id=" + enc(clientId)
				+ "&response_type=code"
				+ "&redirect_uri=" + enc(REDIRECT_URI)
				+ "&scope=" + enc(SCOPES)
				+ "&code_challenge_method=S256"
				+ "&code_challenge=" + challenge
				+ "&state=" + state;

		String code;
		try (ServerSocket server = new ServerSocket(REDIRECT_PORT, 1, InetAddress.getLoopbackAddress())) {
			server.setSoTimeout(180_000);
			Util.getOperatingSystem().open(URI.create(url));
			code = waitForCode(server, state);
		}

		status = "Finishing login...";
		Map<String, String> form = new HashMap<>();
		form.put("grant_type", "authorization_code");
		form.put("code", code);
		form.put("redirect_uri", REDIRECT_URI);
		form.put("client_id", clientId);
		form.put("code_verifier", verifier);
		applyToken(postForm("https://accounts.spotify.com/api/token", form));
		status = "";
		SpotifyBarClient.LOGGER.info("Spotify login OK");
	}

	/** Minimal one-shot HTTP server: reads "GET /callback?code=...&state=..." */
	private String waitForCode(ServerSocket server, String expectedState) throws Exception {
		try (Socket s = server.accept()) {
			s.setSoTimeout(10_000);
			var in = new java.io.BufferedReader(new java.io.InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
			String line = in.readLine(); // GET /callback?x=y HTTP/1.1
			String error = null;
			String code = null;
			String state = null;
			if (line != null) {
				String[] parts = line.split(" ");
				if (parts.length >= 2 && parts[1].contains("?")) {
					for (String kv : parts[1].substring(parts[1].indexOf('?') + 1).split("&")) {
						String[] p = kv.split("=", 2);
						String v = p.length > 1 ? URLDecoder.decode(p[1], StandardCharsets.UTF_8) : "";
						switch (p[0]) {
							case "code" -> code = v;
							case "state" -> state = v;
							case "error" -> error = v;
						}
					}
				}
			}
			boolean ok = code != null && expectedState.equals(state);
			String body = "<html><body style=\"font-family:sans-serif;text-align:center;margin-top:20vh\">"
					+ (ok ? "<h2>Spotify connected</h2><p>You can close this tab and go back to Minecraft.</p>"
					: "<h2>Login failed</h2><p>" + (error != null ? error : "Invalid response") + "</p>")
					+ "</body></html>";
			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			OutputStream out = s.getOutputStream();
			out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: "
					+ bytes.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
			out.write(bytes);
			out.flush();
			if (!ok) throw new Exception(error != null ? error : "invalid callback");
			return code;
		}
	}

	private synchronized void refresh() throws Exception {
		Config cfg = Config.get();
		Map<String, String> form = new HashMap<>();
		form.put("grant_type", "refresh_token");
		form.put("refresh_token", cfg.refreshToken);
		form.put("client_id", cfg.clientId.trim());
		applyToken(postForm("https://accounts.spotify.com/api/token", form));
	}

	private void applyToken(JsonObject json) {
		accessToken = json.get("access_token").getAsString();
		long expiresIn = json.has("expires_in") ? json.get("expires_in").getAsLong() : 3600;
		accessExpiresAtMs = System.currentTimeMillis() + (expiresIn - 60) * 1000L;
		if (json.has("refresh_token")) {
			Config cfg = Config.get();
			cfg.refreshToken = json.get("refresh_token").getAsString();
			cfg.save();
		}
		loggedIn = true;
	}

	public void logout() {
		Config cfg = Config.get();
		cfg.refreshToken = "";
		cfg.save();
		accessToken = "";
		loggedIn = false;
		hasTrack = false;
		status = "Logged out";
	}

	// ------------------------------------------------------------ now playing

	private void pollSafe() {
		try {
			poll();
		} catch (Exception e) {
			SpotifyBarClient.LOGGER.debug("Spotify poll failed", e);
		}
	}

	private void poll() throws Exception {
		if (!loggedIn) return;
		if (System.currentTimeMillis() < backoffUntilMs) return;
		if (System.currentTimeMillis() > accessExpiresAtMs) refresh();

		HttpURLConnection c = open("https://api.spotify.com/v1/me/player/currently-playing", "GET");
		int code = c.getResponseCode();
		if (code == 204) { // nothing playing
			hasTrack = false;
			playing = false;
			return;
		}
		if (code == 401) {
			refresh();
			return;
		}
		if (code == 429) {
			int wait = c.getHeaderField("Retry-After") != null ? Integer.parseInt(c.getHeaderField("Retry-After")) : 10;
			backoffUntilMs = System.currentTimeMillis() + wait * 1000L;
			return;
		}
		if (code == 403) {
						String body = c.getErrorStream() != null ? read(c.getErrorStream()) : "";
			SpotifyBarClient.LOGGER.warn("Spotify 403: {}", body);
			java.util.regex.Matcher m = java.util.regex.Pattern
					.compile("\"message\"\\s*:\\s*\"([^\"]*)\"").matcher(body);
			status = "403: " + (m.find() ? m.group(1) : body);
			return;
		}
		if (code != 200) return;

		JsonObject root = JsonParser.parseString(read(c.getInputStream())).getAsJsonObject();
		JsonElement itemEl = root.get("item");
		if (itemEl == null || itemEl.isJsonNull()) { // ad or unsupported item
			hasTrack = false;
			return;
		}
		JsonObject item = itemEl.getAsJsonObject();
		String name = item.get("name").getAsString();
		StringBuilder artists = new StringBuilder();
		if (item.has("artists")) {
			for (JsonElement a : item.getAsJsonArray("artists")) {
				if (!artists.isEmpty()) artists.append(", ");
				artists.append(a.getAsJsonObject().get("name").getAsString());
			}
		} else if (item.has("show")) { // podcast episode
			artists.append(item.getAsJsonObject("show").get("name").getAsString());
		}
		long dur = item.has("duration_ms") ? item.get("duration_ms").getAsLong() : 0;
		long prog = root.has("progress_ms") && !root.get("progress_ms").isJsonNull() ? root.get("progress_ms").getAsLong() : 0;

		String artUrl = pickImage(item);

		progressAtFetchMs = prog;
		fetchedAtNanos = System.nanoTime();
		durationMs = dur;
		title = name;
		artist = artists.toString();
		playing = root.has("is_playing") && root.get("is_playing").getAsBoolean();
		hasTrack = true;
		if (status.startsWith("403")) status = "";

		if (artUrl != null && !artUrl.equals(currentArtUrl)) {
			currentArtUrl = artUrl;
			loadArt(artUrl);
		}
	}

	private static String pickImage(JsonObject item) {
		JsonArray images = null;
		if (item.has("album")) images = item.getAsJsonObject("album").getAsJsonArray("images");
		else if (item.has("images")) images = item.getAsJsonArray("images");
		if (images == null || images.isEmpty()) return null;
		// choose the smallest image that is still >= 64px
		String best = images.get(0).getAsJsonObject().get("url").getAsString();
		int bestW = Integer.MAX_VALUE;
		for (JsonElement e : images) {
			JsonObject o = e.getAsJsonObject();
			int w = o.has("width") && !o.get("width").isJsonNull() ? o.get("width").getAsInt() : 0;
			if (w >= ART_SIZE && w < bestW) {
				bestW = w;
				best = o.get("url").getAsString();
			}
		}
		return best;
	}

	// ---------------------------------------------------------------- artwork

	private void loadArt(String url) {
		POOL.execute(() -> {
			try {
				HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
				c.setConnectTimeout(8000);
				c.setReadTimeout(8000);
				byte[] data;
				try (InputStream in = c.getInputStream()) {
					data = in.readAllBytes();
				}
				BufferedImage src = ImageIO.read(new ByteArrayInputStream(data));
				if (src == null) return;
				BufferedImage scaled = new BufferedImage(ART_SIZE, ART_SIZE, BufferedImage.TYPE_INT_ARGB);
				Graphics2D g = scaled.createGraphics();
				g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
				g.drawImage(src, 0, 0, ART_SIZE, ART_SIZE, null);
				g.dispose();
				int[] pixels = scaled.getRGB(0, 0, ART_SIZE, ART_SIZE, null, 0, ART_SIZE);

				MinecraftClient mc = MinecraftClient.getInstance();
				mc.execute(() -> {
					NativeImage img = new NativeImage(ART_SIZE, ART_SIZE, false);
					for (int y = 0; y < ART_SIZE; y++) {
						for (int x = 0; x < ART_SIZE; x++) {
							img.setColorArgb(x, y, pixels[y * ART_SIZE + x]);
						}
					}
					if (artTexture != null) mc.getTextureManager().destroyTexture(ART_ID);
					mc.getTextureManager().registerTexture(ART_ID, new NativeImageBackedTexture(img));
					artTexture = ART_ID;
				});
			} catch (Throwable t) {
				SpotifyBarClient.LOGGER.warn("Could not load cover art", t);
			}
		});
	}

	// --------------------------------------------------------------- controls

	public void togglePlay() {
		boolean wasPlaying = playing;
		playing = !wasPlaying; // optimistic
		progressAtFetchMs = progressMs();
		fetchedAtNanos = System.nanoTime();
		control("PUT", wasPlaying ? "pause" : "play");
	}

	public void next() {
		control("POST", "next");
	}

	public void previous() {
		control("POST", "previous");
	}

	private void control(String method, String action) {
		POOL.execute(() -> {
			try {
				if (System.currentTimeMillis() > accessExpiresAtMs) refresh();
				HttpURLConnection c = open("https://api.spotify.com/v1/me/player/" + action, method);
				c.setDoOutput(true);
				c.setFixedLengthStreamingMode(0);
				c.getOutputStream().close();
				int code = c.getResponseCode();
				if (code == 403) status = "Controls need Spotify Premium";
				else if (code == 404) status = "No active Spotify device";
				else if (code >= 400) status = "Spotify error " + code;
				else status = "";
				// refresh the display soon
				POOL.schedule(this::pollSafe, 700, TimeUnit.MILLISECONDS);
			} catch (Exception e) {
				SpotifyBarClient.LOGGER.warn("Control failed", e);
			}
		});
	}

	// ------------------------------------------------------------------ http

	private HttpURLConnection open(String url, String method) throws Exception {
		HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
		c.setRequestMethod(method);
		c.setConnectTimeout(8000);
		c.setReadTimeout(8000);
		c.setRequestProperty("Authorization", "Bearer " + accessToken);
		return c;
	}

	private static JsonObject postForm(String url, Map<String, String> form) throws Exception {
		StringBuilder sb = new StringBuilder();
		for (var e : form.entrySet()) {
			if (!sb.isEmpty()) sb.append('&');
			sb.append(enc(e.getKey())).append('=').append(enc(e.getValue()));
		}
		byte[] body = sb.toString().getBytes(StandardCharsets.UTF_8);
		HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
		c.setRequestMethod("POST");
		c.setConnectTimeout(8000);
		c.setReadTimeout(8000);
		c.setDoOutput(true);
		c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
		c.setFixedLengthStreamingMode(body.length);
		try (OutputStream out = c.getOutputStream()) {
			out.write(body);
		}
		int code = c.getResponseCode();
		if (code != 200) {
			String err = c.getErrorStream() != null ? read(c.getErrorStream()) : "";
			throw new Exception("HTTP " + code + " " + err);
		}
		return JsonParser.parseString(read(c.getInputStream())).getAsJsonObject();
	}

	private static String read(InputStream in) throws Exception {
		try (in) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	private static String enc(String s) {
		return URLEncoder.encode(s, StandardCharsets.UTF_8);
	}
}
