package com.example.spotifybar;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderLayer;

/** Draws the now-playing card. Shared by the HUD and the edit screen. */
public final class SpotifyWidget {
	public static final int WIDTH = 170;
	public static final int HEIGHT = 46;
	private static final int PAD = 6;
	private static final int COVER = 32;
	private static final int BAR_Y = 31;     // progress bar, relative to the card top
	private static final int WAVES_W = 24;

	/** Colors currently on screen {accent rgb, background rgb}; they slide toward the target. */
	private static float[] cur = null;

	private SpotifyWidget() {}

	/** Accent actually being drawn right now (RGB, no alpha). */
	public static int accentNow() {
		return cur == null ? Config.get().accent : pack(cur[0], cur[1], cur[2]);
	}

	/** Progress bar rectangle {x, y, width} for a card drawn at (x, y). */
	public static int[] barRect(int x, int y) {
		int textX = x + PAD + 2 + COVER + 6;
		return new int[]{textX, y + BAR_Y, x + WIDTH - PAD - textX};
	}

	/** Names for the equalizer buttons. The last style is "Off". */
	public static final String[] EQ_STYLES = {"Bars", "Rounded", "Blocks", "Mirror", "Dots", "Line", "Off"};
	public static final String[] EQ_COLORS = {"Accent", "Custom", "Rainbow", "Gradient"};
	public static final int[] EQ_BAR_COUNTS = {4, 6, 8};
	private static final float[] EQ_SPEEDS = {0.6f, 1f, 1.6f};
	private static final int EQ_H = 11;

	/** Names for the shape button. */
	public static final String[] SHAPE_NAMES = {"Square", "Rounded", "Pill", "Cut corners"};

	private static int radius(int shape) {
		return switch (shape) {
			case 1 -> 6;
			case 2 -> 14;
			case 3 -> 7;
			default -> 0;
		};
	}

	/** How far row `row` of a card of height h is cut in from the left/right edge. */
	private static int inset(int row, int h, int shape) {
		int r = radius(shape);
		if (r <= 0) return 0;
		int d = Math.min(row, h - 1 - row);
		if (d >= r) return 0;
		if (shape == 3) return r - d; // diagonal cut
		double dy = r - d - 0.5;
		return (int) Math.ceil(r - Math.sqrt(Math.max(0, r * r - dy * dy)));
	}

	/**
	 * Fills the card shape row by row (rows with the same cut are merged into one fill).
	 * stripeW > 0 draws only a stripe of that width along the left edge instead of the full width.
	 */
	private static void fillShape(DrawContext ctx, int x, int y, int w, int h, int shape, int color, int stripeW) {
		int row = 0;
		while (row < h) {
			int ins = inset(row, h, shape);
			int end = row + 1;
			while (end < h && inset(end, h, shape) == ins) end++;
			int x0 = x + ins;
			int x1 = stripeW > 0 ? x + ins + stripeW : x + w - ins;
			ctx.fill(x0, y + row, x1, y + end, color);
			row = end;
		}
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
		int bgRgb = pack(cur[3], cur[4], cur[5]);
		int bgA = Math.max(30, Math.min(100, cfg.bgOpacity)) * 255 / 100;
		int bg = (bgA << 24) | bgRgb;
		int textC = 0xFF000000 | (cfg.textColor & 0xFFFFFF);
		// secondary text (artist, times): the text color faded toward the background
		int subC = 0xFF000000 | mix(cfg.textColor & 0xFFFFFF, bgRgb, 0.35f);

		int coverX = x + PAD + 2;
		int coverY = y + (HEIGHT - COVER) / 2;

		fillShape(ctx, x, y, WIDTH, HEIGHT, cfg.shape, bg, 0);
		if (cfg.glow && sp.status.isEmpty() && sp.running && sp.hasTrack) {
			drawGlow(ctx, coverX, coverY, pack(cur[0], cur[1], cur[2]), sp.playing);
		}
		fillShape(ctx, x, y, WIDTH, HEIGHT, cfg.shape, accent, 2); // accent stripe following the edge

		int tx = x + PAD + 2;
		int tw = WIDTH - PAD * 2 - 2;

		if (!sp.status.isEmpty()) {
			ctx.drawText(tr, "Spotify", tx, y + 12, textC, false);
			ctx.drawText(tr, trim(tr, sp.status, tw, 0), tx, y + 24, subC, false);
			return;
		}
		if (!sp.running) {
			ctx.drawText(tr, "Spotify", tx, y + 12, textC, false);
			ctx.drawText(tr, trim(tr, "Open the Spotify app", tw, 1), tx, y + 24, subC, false);
			return;
		}
		if (!sp.hasTrack) {
			ctx.drawText(tr, "Nothing playing", tx, y + 12, textC, false);
			ctx.drawText(tr, trim(tr, "Press play in Spotify", tw, 2), tx, y + 24, subC, false);
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
		boolean eqOn = cfg.eqStyle >= 0 && cfg.eqStyle < EQ_STYLES.length - 1;
		ctx.drawText(tr, trim(tr, sp.artist, textW - (eqOn ? WAVES_W + 4 : 0), 4), textX, y + 16, subC, false);

		drawEqualizer(ctx, x + WIDTH - PAD - WAVES_W, y + 27, sp.playing, accent & 0xFFFFFF, subC, cfg);

		// progress bar + times
		long dur = sp.durationMs;
		long pos = sp.positionMs();
		int barY = y + BAR_Y;
		ctx.fill(textX, barY, textX + textW, barY + 3, 0x55000000 | (subC & 0xFFFFFF));
		if (dur > 0) {
			int filled = (int) (textW * Math.min(1.0, pos / (double) dur));
			ctx.fill(textX, barY, textX + filled, barY + 3, accent);
			ctx.fill(textX + Math.max(0, filled - 1), barY - 1, textX + filled + 1, barY + 4, textC); // knob
		}

		String left = fmt(pos);
		String mid = dur > 0 ? "-" + fmt(Math.max(0, dur - pos)) : "";
		String right = dur > 0 ? fmt(dur) : "--:--";
		float sc = 0.75f;
		int innerW = (int) (textW / sc);
		var m = ctx.getMatrices();
		m.push();
		m.translate(textX, barY + 6, 0);
		m.scale(sc, sc, 1f);
		ctx.drawText(tr, left, 0, 0, textC, false);
		if (!mid.isEmpty()) ctx.drawText(tr, mid, (innerW - tr.getWidth(mid)) / 2, 0, subC, false);
		ctx.drawText(tr, right, innerW - tr.getWidth(right), 0, subC, false);
		m.pop();
	}

	private static String fmt(long ms) {
		long sec = Math.max(0, ms / 1000);
		long h = sec / 3600, m = (sec / 60) % 60, s = sec % 60;
		return h > 0 ? String.format("%d:%02d:%02d", h, m, s) : String.format("%d:%02d", sec / 60, s);
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

	/** Color of bar i for the chosen equalizer color mode (RGB). */
	private static int eqRgb(Config cfg, int i, int n, int accentRgb, long now) {
		return switch (cfg.eqColorMode) {
			case 1 -> cfg.eqColor & 0xFFFFFF;
			case 2 -> SpotifyClient.hsvToRgb((i / (float) n + (now % 6000) / 6000f) % 1f, 0.75f, 1f);
			case 3 -> mix(accentRgb, cfg.eqColor & 0xFFFFFF, n > 1 ? i / (float) (n - 1) : 0f);
			default -> accentRgb;
		};
	}

	/**
	 * Animated equalizer. It dances while music plays and sits flat when paused.
	 * (The movement is an animation - a mod can't read Spotify's audio.)
	 */
	private static void drawEqualizer(DrawContext ctx, int x, int baseY, boolean playing,
									  int accentRgb, int idleArgb, Config cfg) {
		int style = cfg.eqStyle;
		if (style < 0 || style >= EQ_STYLES.length - 1) return; // off
		int n = EQ_BAR_COUNTS[Math.floorMod(cfg.eqBars, EQ_BAR_COUNTS.length)];
		float speed = EQ_SPEEDS[Math.floorMod(cfg.eqSpeed, EQ_SPEEDS.length)];
		int slot = WAVES_W / n;
		int bw = Math.max(1, slot - 1);
		long now = System.currentTimeMillis();
		double t = now * (double) speed;

		int[] hh = new int[n];
		int[] col = new int[n];
		for (int i = 0; i < n; i++) {
			double level = playing
					? 0.2 + 0.8 * Math.abs(Math.sin(t / 170.0 + i * 1.7) * Math.cos(t / 290.0 + i * 0.9))
					: 0.12;
			hh[i] = Math.max(1, (int) (EQ_H * level));
			col[i] = playing ? 0xFF000000 | eqRgb(cfg, i, n, accentRgb, now) : idleArgb;
		}

		for (int i = 0; i < n; i++) {
			int bx = x + i * slot;
			int top = baseY - hh[i];
			switch (style) {
				case 0 -> ctx.fill(bx, top, bx + bw, baseY, col[i]); // bars
				case 1 -> { // rounded bars: corners cut off
					if (bw >= 3 && hh[i] >= 3) {
						ctx.fill(bx + 1, top, bx + bw - 1, top + 1, col[i]);
						ctx.fill(bx, top + 1, bx + bw, baseY - 1, col[i]);
						ctx.fill(bx + 1, baseY - 1, bx + bw - 1, baseY, col[i]);
					} else {
						ctx.fill(bx, top, bx + bw, baseY, col[i]);
					}
				}
				case 2 -> { // blocks: LED-style segments
					int segs = Math.max(1, hh[i] / 3);
					for (int sgm = 0; sgm < segs; sgm++) {
						int sy = baseY - sgm * 3;
						ctx.fill(bx, sy - 2, bx + bw, sy, col[i]);
					}
				}
				case 3 -> { // mirror: grows up and down from the middle
					int mid = baseY - EQ_H / 2;
					int half = Math.max(1, hh[i] / 2);
					ctx.fill(bx, mid - half, bx + bw, mid + half, col[i]);
				}
				case 4 -> { // dots
					int ds = Math.max(2, bw);
					int dy = Math.min(top, baseY - ds);
					if (ds >= 3) {
						ctx.fill(bx + 1, dy, bx + ds - 1, dy + 1, col[i]);
						ctx.fill(bx, dy + 1, bx + ds, dy + ds - 1, col[i]);
						ctx.fill(bx + 1, dy + ds - 1, bx + ds - 1, dy + ds, col[i]);
					} else {
						ctx.fill(bx, dy, bx + ds, dy + ds, col[i]);
					}
				}
				case 5 -> { // line: stepped wave through the bar heights
					ctx.fill(bx, top, bx + slot, top + 1, col[i]);
					if (i + 1 < n) {
						int nextTop = baseY - hh[i + 1];
						ctx.fill(bx + slot, Math.min(top, nextTop), bx + slot + 1, Math.max(top, nextTop) + 1, col[i]);
					}
				}
				default -> { }
			}
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
