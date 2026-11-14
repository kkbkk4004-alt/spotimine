package com.example.spotifybar;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/**
 * Opened with the keybind (default J). Drag the card anywhere, use the
 * controls under it and pick its colors.
 */
public class SpotifyScreen extends Screen {
	private static final int BTN_W = 30;
	private static final int BTN_H = 16;
	private static final int SW = 14;   // swatch size
	private static final int SW_GAP = 4;

	private static final int[] ACCENTS = {
			0x1DB954, 0x3B82F6, 0xA855F7, 0xEC4899, 0xEF4444, 0xF97316, 0xFACC15, 0xFFFFFF
	};
	private static final int[] BACKGROUNDS = {
			0x101010, 0x000000, 0x1E293B, 0x2E1065, 0x3B0A0A, 0x052E16, 0x422006, 0x4A4A4A
	};

	private ButtonWidget matchButton;
	private static final int[] TEXTS = {
			0xFFFFFF, 0xFACC15, 0x22D3EE, 0xF472B6, 0x86EFAC, 0xFDBA74, 0xC4B5FD, 0x000000
	};

	private boolean dragging = false;
	private int dragDX, dragDY;

	public SpotifyScreen() {
		super(Text.literal("Spotify Bar"));
	}

	@Override
	protected void init() {
		int cx = this.width / 2;
		int top = this.height / 2 + 10;

		addDrawableChild(ButtonWidget.builder(Text.literal("Hide / show bar"), b -> {
			Config.get().visible = !Config.get().visible;
			Config.get().save();
		}).dimensions(cx - 120, top, 118, 20).build());

		addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> close())
				.dimensions(cx + 2, top, 118, 20).build());

		matchButton = ButtonWidget.builder(Text.literal("Match cover colors"), b -> {
			Config.get().matchCover = !Config.get().matchCover;
			Config.get().save();
		}).dimensions(cx - 120, top + 24, 240, 20).build();
		addDrawableChild(matchButton);
	}

	// swatch rows are centered under the buttons
	private int rowX(int count) {
		return this.width / 2 - (count * SW + (count - 1) * SW_GAP) / 2;
	}

	private int accentRowY() {
		return this.height / 2 + 10 + 60;
	}

	private int bgRowY() {
		return this.height / 2 + 10 + 86;
	}

	private int textRowY() {
		return this.height / 2 + 10 + 112;
	}

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		ctx.fill(0, 0, this.width, this.height, 0x66000000);

		SpotifyClient sp = SpotifyBarClient.spotify;
		Config cfg = Config.get();
		clamp(cfg);
		matchButton.setMessage(Text.literal("Match cover colors: " + (cfg.matchCover ? "ON" : "OFF")));
		SpotifyWidget.draw(ctx, cfg.x, cfg.y);

		if (sp.running) {
			int cy = cfg.y + SpotifyWidget.HEIGHT + 3;
			int bx = controlsX(cfg);
			int accent = 0xFF000000 | SpotifyWidget.accentNow();
			drawButton(ctx, bx, cy, "|<", mouseX, mouseY, accent);
			drawButton(ctx, bx + BTN_W + 2, cy, sp.playing ? "||" : ">", mouseX, mouseY, accent);
			drawButton(ctx, bx + (BTN_W + 2) * 2, cy, ">|", mouseX, mouseY, accent);
		}

		ctx.drawCenteredTextWithShadow(this.textRenderer,
				"Drag the card to move it", this.width / 2, this.height / 2 - 10, 0xFFFFFFFF);

		// color pickers
		ctx.drawCenteredTextWithShadow(this.textRenderer, "Accent color",
				this.width / 2, accentRowY() - 11, 0xFFFFFFFF);
		drawSwatches(ctx, ACCENTS, rowX(ACCENTS.length), accentRowY(), cfg.accent);
		ctx.drawCenteredTextWithShadow(this.textRenderer, "Background color",
				this.width / 2, bgRowY() - 11, 0xFFFFFFFF);
		drawSwatches(ctx, BACKGROUNDS, rowX(BACKGROUNDS.length), bgRowY(), cfg.background);
		ctx.drawCenteredTextWithShadow(this.textRenderer, "Text color",
				this.width / 2, textRowY() - 11, 0xFFFFFFFF);
		drawSwatches(ctx, TEXTS, rowX(TEXTS.length), textRowY(), cfg.textColor);

		super.render(ctx, mouseX, mouseY, delta);
	}

	private void drawSwatches(DrawContext ctx, int[] colors, int x, int y, int selected) {
		for (int i = 0; i < colors.length; i++) {
			int sx = x + i * (SW + SW_GAP);
			boolean sel = (colors[i] & 0xFFFFFF) == (selected & 0xFFFFFF);
			ctx.fill(sx - 1, y - 1, sx + SW + 1, y + SW + 1, sel ? 0xFFFFFFFF : 0xFF000000);
			ctx.fill(sx, y, sx + SW, y + SW, 0xFF000000 | colors[i]);
		}
	}

	private int controlsX(Config cfg) {
		int total = BTN_W * 3 + 4;
		return cfg.x + (SpotifyWidget.WIDTH - total) / 2;
	}

	private void drawButton(DrawContext ctx, int x, int y, String label, int mx, int my, int accent) {
		boolean hover = mx >= x && mx < x + BTN_W && my >= y && my < y + BTN_H;
		ctx.fill(x, y, x + BTN_W, y + BTN_H, hover ? accent : 0xFF2A2A2A);
		ctx.drawCenteredTextWithShadow(this.textRenderer, label, x + BTN_W / 2, y + 4, 0xFFFFFFFF);
	}

	private int swatchAt(int[] colors, int x, int y, double mx, double my) {
		if (my < y || my >= y + SW) return -1;
		for (int i = 0; i < colors.length; i++) {
			int sx = x + i * (SW + SW_GAP);
			if (mx >= sx && mx < sx + SW) return i;
		}
		return -1;
	}

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		if (super.mouseClicked(mx, my, button)) return true;
		if (button != 0) return false;

		Config cfg = Config.get();
		SpotifyClient sp = SpotifyBarClient.spotify;

		int a = swatchAt(ACCENTS, rowX(ACCENTS.length), accentRowY(), mx, my);
		if (a >= 0) {
			cfg.accent = ACCENTS[a];
			cfg.matchCover = false; // picking a color yourself turns cover matching off
			cfg.save();
			return true;
		}
		int b = swatchAt(BACKGROUNDS, rowX(BACKGROUNDS.length), bgRowY(), mx, my);
		if (b >= 0) {
			cfg.background = BACKGROUNDS[b];
			cfg.matchCover = false;
			cfg.save();
			return true;
		}

		int t = swatchAt(TEXTS, rowX(TEXTS.length), textRowY(), mx, my);
		if (t >= 0) {
			cfg.textColor = TEXTS[t];
			cfg.save();
			return true;
		}

		if (sp.running) {
			int cy = cfg.y + SpotifyWidget.HEIGHT + 3;
			int bx = controlsX(cfg);
			if (my >= cy && my < cy + BTN_H) {
				if (mx >= bx && mx < bx + BTN_W) { sp.previous(); return true; }
				if (mx >= bx + BTN_W + 2 && mx < bx + BTN_W * 2 + 2) { sp.togglePlay(); return true; }
				if (mx >= bx + (BTN_W + 2) * 2 && mx < bx + (BTN_W + 2) * 2 + BTN_W) { sp.next(); return true; }
			}
		}

		if (mx >= cfg.x && mx < cfg.x + SpotifyWidget.WIDTH && my >= cfg.y && my < cfg.y + SpotifyWidget.HEIGHT) {
			dragging = true;
			dragDX = (int) mx - cfg.x;
			dragDY = (int) my - cfg.y;
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
		if (dragging && button == 0) {
			Config cfg = Config.get();
			cfg.x = (int) mx - dragDX;
			cfg.y = (int) my - dragDY;
			clamp(cfg);
			return true;
		}
		return super.mouseDragged(mx, my, button, dx, dy);
	}

	@Override
	public boolean mouseReleased(double mx, double my, int button) {
		if (dragging && button == 0) {
			dragging = false;
			Config.get().save();
			return true;
		}
		return super.mouseReleased(mx, my, button);
	}

	private void clamp(Config cfg) {
		cfg.x = Math.max(0, Math.min(cfg.x, this.width - SpotifyWidget.WIDTH));
		cfg.y = Math.max(0, Math.min(cfg.y, this.height - SpotifyWidget.HEIGHT - 20));
	}

	@Override
	public boolean shouldPause() {
		return false;
	}
}
