package com.example.spotifybar;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderLayer;

/** Draws the now-playing card. Shared by the HUD and the edit screen. */
public final class SpotifyWidget {
	public static final int WIDTH = 170;
	public static final int HEIGHT = 40;
	private static final int PAD = 6;
	private static final int COVER = 32;

	/** Colors currently on screen {accent rgb, background rgb}; they slide toward the target. */
	private static float[] cur = null;

	private SpotifyWidget() {}

	/** Accent/background actually being drawn right now (RGB, no alpha). */
	public static int accentNow() {
		return cur == null ? Config.get().accent : pack(cur[0], cur[1], cur[2]);
	}

	private static int mix(int a, int b, float t) {
		int r = Math.round(((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
		int g = Math.round(((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
		int bl = Math.round((a & 255) * (1 - t) + (b & 255) * t);
		return (r << 16) | (g << 8) | bl;
	}

	private static int pack(float r, float g, float b) {
		return ((int) r << 16) | ((int) g << 8) | (int) b;
	}

	private static void updateColors(Config cfg, SpotifyClient sp) {
		int tA, tB;
		if (cfg.matchCover && sp.hasTrack && sp.hasCoverColors) {
			tA = sp.coverAccent;
			tB = sp.coverBg;
		} else {
			tA = cfg.accent;
			tB = cfg.background;
		}
		float[] target = {
				(tA >> 16) & 255, (tA >> 8) & 255, tA & 255,
				(tB >> 16) & 255, (tB >> 8) & 255, tB & 255
		};
		if (cur == null) {
			cur = target.clone();
			return;
		}
		for (int i = 0; i < 6; i++) cur[i] += (target[i] - cur[i]) * 0.08f;
	}

	public static void draw(DrawContext ctx, int x, int y) {
		TextRenderer tr = MinecraftClient.getInstance().textRenderer;
		SpotifyClient sp = SpotifyBarClient.spotify;
		Config cfg = Config.get();

		updateColors(cfg, sp);
		int accent = 0xFF000000 | pack(cur[0], cur[1], cur[2]);
		int bg = 0xB0000000 | pack(cur[3], cur[4], cur[5]);

		int bgRgb = pack(cur[3], cur[4], cur[5]);
		int textC = 0xFF000000 | (cfg.textColor & 0xFFFFFF);
		// secondary text (artist, "Paused"): the text color faded toward the background
		int subC = 0xFF000000 | mix(cfg.textColor & 0xFFFFFF, bgRgb, 0.35f);

		int coverX = x + PAD + 2;
		int coverY = y + (HEIGHT - COVER) / 2;

		ctx.fill(x, y, x + WIDTH, y + HEIGHT, bg);
		if (cfg.glow && sp.status.isEmpty() && sp.running && sp.hasTrack) {
			drawGlow(ctx, coverX, coverY, pack(cur[0], cur[1], cur[2]), sp.playing);
		}
		ctx.fill(x, y, x + 2, y + HEIGHT, accent); // accent stripe

		int tx = x + PAD + 2;
		int tw = WIDTH - PAD * 2 - 2;

		if (!sp.status.isEmpty()) {
			ctx.drawText(tr, "Spotify", tx, y + 8, textC, false);
			ctx.drawText(tr, trim(tr, sp.status, tw, 0), tx, y + 20, subC, false);
			return;
		}
		if (!sp.running) {
			ctx.drawText(tr, "Spotify", tx, y + 8, textC, false);
			ctx.drawText(tr, trim(tr, "Open the Spotify app", tw, 1), tx, y + 20, subC, false);
			return;
		}
		if (!sp.hasTrack) {
			ctx.drawText(tr, "Nothing playing", tx, y + 8, textC, false);
			ctx.drawText(tr, trim(tr, "Press play in Spotify", tw, 2), tx, y + 20, subC, false);
			return;
		}

		// cover (or a dark placeholder while it loads / when none was found)
		if (sp.coverTexture != null) {
			var m = ctx.getMatrices();
			m.push();
			m.translate(coverX, coverY, 0);
			float s = COVER / (float) SpotifyClient.ART_SIZE;
			m.scale(s, s, 1f);
			ctx.drawTexture(RenderLayer::getGuiTextured, sp.coverTexture, 0, 0, 0f, 0f,
					SpotifyClient.ART_SIZE, SpotifyClient.ART_SIZE,
					SpotifyClient.ART_SIZE, SpotifyClient.ART_SIZE);
			m.pop();
		} else {
			ctx.fill(coverX, coverY, coverX + COVER, coverY + COVER, 0x66000000);
		}

		int textX = coverX + COVER + 6;
		int textW = x + WIDTH - PAD - textX;

		ctx.drawText(tr, trim(tr, sp.title, textW, 3), textX, y + 5, textC, false);
		ctx.drawText(tr, trim(tr, sp.artist, textW, 4), textX, y + 16, subC, false);

		ctx.getMatrices().push();
		ctx.getMatrices().translate(textX, y + 27, 0);
		ctx.getMatrices().scale(0.75f, 0.75f, 1f);
		ctx.drawText(tr, sp.playing ? "Now playing" : "Paused", 0, 0, sp.playing ? accent : subC, false);
		ctx.getMatrices().pop();

		drawWaves(ctx, x + WIDTH - PAD - 6 * 4 + 2, y + HEIGHT - 5, sp.playing, accent, subC);
	}

	/**
	 * Soft "LED" glow behind the cover in the cover's color. Stacked translucent
	 * rectangles fade out from the cover's edge; the brightness pulses while playing.
	 */
	private static void drawGlow(DrawContext ctx, int cx, int cy, int rgb, boolean playing) {
		long t = System.currentTimeMillis();
		double pulse = playing
				? 0.8 + 0.2 * Math.sin(t / 240.0) * (0.6 + 0.4 * Math.sin(t / 730.0))
				: 0.45;
		for (int i = 10; i >= 1; i--) {
			float f = 1f - i / 11f;
			int a = (int) (38 * pulse * f * f);
			if (a <= 0) continue;
			ctx.fill(cx - i, cy - i, cx + COVER + i, cy + COVER + i, (a << 24) | (rgb & 0xFFFFFF));
		}
	}

	/** Animated equalizer bars. They dance while music plays and sit flat when paused. */
	private static void drawWaves(DrawContext ctx, int x, int baseY, boolean playing, int accent, int idle) {
		long t = System.currentTimeMillis();
		for (int i = 0; i < 6; i++) {
			double h = playing
					? 0.2 + 0.8 * Math.abs(Math.sin(t / 170.0 + i * 1.7) * Math.cos(t / 290.0 + i * 0.9))
					: 0.12;
			int hh = Math.max(1, (int) (11 * h));
			ctx.fill(x + i * 4, baseY - hh, x + i * 4 + 2, baseY, playing ? accent : idle);
		}
	}

	// trimming text every frame is wasteful, so remember the last result per text slot
	private static final String[] TRIM_SRC = new String[8];
	private static final String[] TRIM_OUT = new String[8];
	private static final int[] TRIM_W = new int[8];

	private static String trim(TextRenderer tr, String s, int maxW, int slot) {
		if (s.equals(TRIM_SRC[slot]) && TRIM_W[slot] == maxW) return TRIM_OUT[slot];
		String out = s;
		if (tr.getWidth(out) > maxW) {
			String ell = "...";
			while (!out.isEmpty() && tr.getWidth(out + ell) > maxW) out = out.substring(0, out.length() - 1);
			out = out + ell;
		}
		TRIM_SRC[slot] = s;
		TRIM_OUT[slot] = out;
		TRIM_W[slot] = maxW;
		return out;
	}
}
