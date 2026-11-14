package com.example.spotifybar;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderLayer;

/** Draws the now-playing card. Shared by the HUD and the edit screen. */
public final class SpotifyWidget {
	public static final int WIDTH = 170;
	public static final int HEIGHT = 46;
	private static final int ART = 36;
	private static final int PAD = 5;

	private static final int BG = 0xB0101010;
	private static final int GREEN = 0xFF1DB954;
	private static final int WHITE = 0xFFFFFFFF;
	private static final int GRAY = 0xFFB3B3B3;
	private static final int BAR_BG = 0xFF535353;

	private SpotifyWidget() {}

	public static void draw(DrawContext ctx, int x, int y) {
		MinecraftClient mc = MinecraftClient.getInstance();
		TextRenderer tr = mc.textRenderer;
		SpotifyClient sp = SpotifyBarClient.spotify;

		ctx.fill(x, y, x + WIDTH, y + HEIGHT, BG);
		ctx.fill(x, y, x + 2, y + HEIGHT, GREEN); // accent stripe

		int artX = x + PAD + 2;
		int artY = y + PAD;
		int textX = artX + ART + 6;
		int textW = x + WIDTH - PAD - textX;

		if (!sp.loggedIn) {
			ctx.fill(artX, artY, artX + ART, artY + ART, 0xFF282828);
			ctx.drawText(tr, "Spotify", textX, y + 12, WHITE, false);
			ctx.drawText(tr, trim(tr, "Not logged in", textW), textX, y + 24, GRAY, false);
			return;
		}

		// cover
		if (sp.hasTrack && sp.artTexture != null) {
			var m = ctx.getMatrices();
			m.push();
			m.translate(artX, artY, 0);
			float s = ART / (float) SpotifyClient.ART_SIZE;
			m.scale(s, s, 1f);
			ctx.drawTexture(RenderLayer::getGuiTextured, sp.artTexture, 0, 0, 0f, 0f,
					SpotifyClient.ART_SIZE, SpotifyClient.ART_SIZE,
					SpotifyClient.ART_SIZE, SpotifyClient.ART_SIZE);
			m.pop();
		} else {
			ctx.fill(artX, artY, artX + ART, artY + ART, 0xFF282828);
		}

		if (!sp.hasTrack) {
			ctx.drawText(tr, "Nothing playing", textX, y + 12, WHITE, false);
			ctx.drawText(tr, trim(tr, "Open Spotify and play", textW), textX, y + 24, GRAY, false);
			return;
		}

		ctx.drawText(tr, trim(tr, sp.title, textW), textX, y + 6, WHITE, false);
		ctx.drawText(tr, trim(tr, sp.artist, textW), textX, y + 17, GRAY, false);

		// progress bar
		long dur = Math.max(1, sp.durationMs);
		long pos = sp.progressMs();
		int barY = y + HEIGHT - 12;
		int barX = textX;
		int barW = textW;
		ctx.fill(barX, barY, barX + barW, barY + 2, BAR_BG);
		int filled = (int) (barW * (pos / (double) dur));
		ctx.fill(barX, barY, barX + filled, barY + 2, sp.playing ? GREEN : WHITE);

		String left = fmt(pos);
		String right = fmt(sp.durationMs);
		ctx.getMatrices().push();
		ctx.getMatrices().translate(barX, barY + 4, 0);
		ctx.getMatrices().scale(0.75f, 0.75f, 1f);
		ctx.drawText(tr, left, 0, 0, GRAY, false);
		ctx.drawText(tr, right, (int) ((barW) / 0.75f) - tr.getWidth(right), 0, GRAY, false);
		ctx.getMatrices().pop();
	}

	private static String trim(TextRenderer tr, String s, int maxW) {
		if (tr.getWidth(s) <= maxW) return s;
		String ell = "...";
		while (!s.isEmpty() && tr.getWidth(s + ell) > maxW) s = s.substring(0, s.length() - 1);
		return s + ell;
	}

	private static String fmt(long ms) {
		long sec = ms / 1000;
		return (sec / 60) + ":" + String.format("%02d", sec % 60);
	}
}
