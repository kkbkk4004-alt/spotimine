package com.example.spotifybar;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

/** Draws the now-playing card. Shared by the HUD and the edit screen. */
public final class SpotifyWidget {
	public static final int WIDTH = 150;
	public static final int HEIGHT = 40;
	private static final int PAD = 6;

	private static final int WHITE = 0xFFFFFFFF;
	private static final int GRAY = 0xFFB3B3B3;

	private SpotifyWidget() {}

	public static void draw(DrawContext ctx, int x, int y) {
		TextRenderer tr = MinecraftClient.getInstance().textRenderer;
		SpotifyClient sp = SpotifyBarClient.spotify;
		Config cfg = Config.get();
		int accent = 0xFF000000 | (cfg.accent & 0xFFFFFF);
		int bg = 0xB0000000 | (cfg.background & 0xFFFFFF);

		ctx.fill(x, y, x + WIDTH, y + HEIGHT, bg);
		ctx.fill(x, y, x + 2, y + HEIGHT, accent); // accent stripe

		int tx = x + PAD + 2;
		int tw = WIDTH - PAD * 2 - 2;

		if (!sp.status.isEmpty()) {
			ctx.drawText(tr, "Spotify", tx, y + 8, WHITE, false);
			ctx.drawText(tr, trim(tr, sp.status, tw), tx, y + 20, GRAY, false);
			return;
		}
		if (!sp.running) {
			ctx.drawText(tr, "Spotify", tx, y + 8, WHITE, false);
			ctx.drawText(tr, trim(tr, "Open the Spotify app", tw), tx, y + 20, GRAY, false);
			return;
		}
		if (!sp.hasTrack) {
			ctx.drawText(tr, "Nothing playing", tx, y + 8, WHITE, false);
			ctx.drawText(tr, trim(tr, "Press play in Spotify", tw), tx, y + 20, GRAY, false);
			return;
		}

		ctx.drawText(tr, trim(tr, sp.title, tw), tx, y + 5, WHITE, false);
		ctx.drawText(tr, trim(tr, sp.artist, tw), tx, y + 16, GRAY, false);

		ctx.getMatrices().push();
		ctx.getMatrices().translate(tx, y + 27, 0);
		ctx.getMatrices().scale(0.75f, 0.75f, 1f);
		ctx.drawText(tr, sp.playing ? "Now playing" : "Paused", 0, 0, sp.playing ? accent : GRAY, false);
		ctx.getMatrices().pop();

		drawWaves(ctx, x + WIDTH - PAD - 6 * 4 + 2, y + HEIGHT - 5, sp.playing, accent);
	}

	/** Animated equalizer bars. They dance while music plays and sit flat when paused. */
	private static void drawWaves(DrawContext ctx, int x, int baseY, boolean playing, int accent) {
		long t = System.currentTimeMillis();
		for (int i = 0; i < 6; i++) {
			double h = playing
					? 0.2 + 0.8 * Math.abs(Math.sin(t / 170.0 + i * 1.7) * Math.cos(t / 290.0 + i * 0.9))
					: 0.12;
			int hh = Math.max(1, (int) (11 * h));
			ctx.fill(x + i * 4, baseY - hh, x + i * 4 + 2, baseY, playing ? accent : GRAY);
		}
	}

	private static String trim(TextRenderer tr, String s, int maxW) {
		if (tr.getWidth(s) <= maxW) return s;
		String ell = "...";
		while (!s.isEmpty() && tr.getWidth(s + ell) > maxW) s = s.substring(0, s.length() - 1);
		return s + ell;
	}
}
