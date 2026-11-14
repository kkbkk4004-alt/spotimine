package com.example.spotifybar;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.Screen;

/**
 * While the chat is open the mouse cursor is free, so you can drag the card around,
 * click previous / play-pause / next under it, and click the time bar to sync it.
 */
public final class ChatHook {
	private static final int BTN_W = 30;
	private static final int BTN_H = 16;

	private static boolean dragging = false;
	private static int dragDX, dragDY;

	private ChatHook() {}

	public static void register() {
		ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
			if (!(screen instanceof ChatScreen)) return;
			dragging = false;
			ScreenMouseEvents.allowMouseClick(screen).register(ChatHook::onClick);
			ScreenMouseEvents.allowMouseRelease(screen).register(ChatHook::onRelease);
			ScreenEvents.afterRender(screen).register(ChatHook::onRender);
		});
	}

	private static int controlsX(Config cfg) {
		return cfg.x + (SpotifyWidget.WIDTH - (BTN_W * 3 + 4)) / 2;
	}

	/** Returns false to swallow the click (so the chat doesn't also react to it). */
	private static boolean onClick(Screen screen, double mx, double my, int button) {
		Config cfg = Config.get();
		if (!cfg.visible || button != 0) return true;
		SpotifyClient sp = SpotifyBarClient.spotify;

		// time bar: sync the elapsed time
		if (sp.running && sp.hasTrack && sp.durationMs > 0) {
			int[] bar = SpotifyWidget.barRect(cfg.x, cfg.y);
			if (mx >= bar[0] && mx <= bar[0] + bar[2] && my >= bar[1] - 4 && my <= bar[1] + 7) {
				sp.seekTo((long) ((mx - bar[0]) / bar[2] * sp.durationMs));
				return false;
			}
		}

		// previous / play-pause / next
		if (sp.running) {
			int cy = cfg.y + SpotifyWidget.HEIGHT + 3;
			int bx = controlsX(cfg);
			if (my >= cy && my < cy + BTN_H) {
				if (mx >= bx && mx < bx + BTN_W) { sp.previous(); return false; }
				if (mx >= bx + BTN_W + 2 && mx < bx + BTN_W * 2 + 2) { sp.togglePlay(); return false; }
				if (mx >= bx + (BTN_W + 2) * 2 && mx < bx + (BTN_W + 2) * 2 + BTN_W) { sp.next(); return false; }
			}
		}

		// drag the card
		if (mx >= cfg.x && mx < cfg.x + SpotifyWidget.WIDTH && my >= cfg.y && my < cfg.y + SpotifyWidget.HEIGHT) {
			dragging = true;
			dragDX = (int) mx - cfg.x;
			dragDY = (int) my - cfg.y;
			return false;
		}
		return true;
	}

	private static boolean onRelease(Screen screen, double mx, double my, int button) {
		if (dragging && button == 0) {
			dragging = false;
			Config.get().save();
			return false;
		}
		return true;
	}

	private static void onRender(Screen screen, DrawContext ctx, int mouseX, int mouseY, float tickDelta) {
		Config cfg = Config.get();
		if (!cfg.visible) return;
		MinecraftClient mc = MinecraftClient.getInstance();
		SpotifyClient sp = SpotifyBarClient.spotify;

		if (dragging) {
			if (!mc.mouse.wasLeftButtonClicked()) { // released somewhere we didn't see
				dragging = false;
				cfg.save();
			} else {
				cfg.x = Math.max(0, Math.min(mouseX - dragDX, screen.width - SpotifyWidget.WIDTH));
				cfg.y = Math.max(0, Math.min(mouseY - dragDY, screen.height - SpotifyWidget.HEIGHT - 20));
			}
		}

		if (sp.running) {
			int cy = cfg.y + SpotifyWidget.HEIGHT + 3;
			int bx = controlsX(cfg);
			int accent = 0xFF000000 | SpotifyWidget.accentNow();
			drawButton(ctx, mc, bx, cy, "|<", mouseX, mouseY, accent);
			drawButton(ctx, mc, bx + BTN_W + 2, cy, sp.playing ? "||" : ">", mouseX, mouseY, accent);
			drawButton(ctx, mc, bx + (BTN_W + 2) * 2, cy, ">|", mouseX, mouseY, accent);
		}
	}

	private static void drawButton(DrawContext ctx, MinecraftClient mc, int x, int y, String label,
								   int mx, int my, int accent) {
		boolean hover = mx >= x && mx < x + BTN_W && my >= y && my < y + BTN_H;
		ctx.fill(x, y, x + BTN_W, y + BTN_H, hover ? accent : 0xFF2A2A2A);
		ctx.drawCenteredTextWithShadow(mc.textRenderer, label, x + BTN_W / 2, y + 4, 0xFFFFFFFF);
	}
}
