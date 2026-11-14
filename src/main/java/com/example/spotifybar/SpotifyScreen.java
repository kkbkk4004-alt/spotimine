package com.example.spotifybar;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

/**
 * Opened with the keybind (default J). Drag the card anywhere, use the
 * controls under it and pick its colors (palette or any hex color).
 */
public class SpotifyScreen extends Screen {
	private static final int BTN_W = 30;
	private static final int BTN_H = 16;
	private static final int SW = 14;    // swatch size
	private static final int SW_GAP = 4;
	private static final int COLS = 12;
	private static final int ROWS = 4;

	private static final int TAB_ACCENT = 0, TAB_BG = 1, TAB_TEXT = 2;
	private static final String[] TAB_NAMES = {"Accent", "Background", "Text"};

	private final int[] brightPalette = buildPalette(false);
	private final int[] darkPalette = buildPalette(true);

	private int tab = TAB_ACCENT;
	private int top;
	private boolean updatingField = false;

	private ButtonWidget matchButton;
	private ButtonWidget glowButton;
	private ButtonWidget shapeButton;
	private final ButtonWidget[] tabButtons = new ButtonWidget[3];
	private TextFieldWidget hexField;

	private boolean dragging = false;
	private int dragDX, dragDY;

	public SpotifyScreen() {
		super(Text.literal("Spotify Bar"));
	}

	/** 12 columns of hues x 4 rows: vivid, soft, deep and grays (or dark tints for backgrounds). */
	private static int[] buildPalette(boolean dark) {
		int[] p = new int[COLS * ROWS];
		float[][] rows = dark
				? new float[][]{{0.7f, 0.12f}, {0.7f, 0.2f}, {0.6f, 0.3f}}
				: new float[][]{{1f, 1f}, {0.45f, 1f}, {1f, 0.6f}};
		for (int c = 0; c < COLS; c++) {
			for (int r = 0; r < 3; r++) {
				p[r * COLS + c] = SpotifyClient.hsvToRgb(c / (float) COLS, rows[r][0], rows[r][1]);
			}
			float v = c / (float) (COLS - 1) * (dark ? 0.35f : 1f); // grays
			p[3 * COLS + c] = SpotifyClient.hsvToRgb(0f, 0f, v);
		}
		return p;
	}

	private int[] palette() {
		return tab == TAB_BG ? darkPalette : brightPalette;
	}

	private int currentColor() {
		Config cfg = Config.get();
		return switch (tab) {
			case TAB_BG -> cfg.background;
			case TAB_TEXT -> cfg.textColor;
			default -> cfg.accent;
		} & 0xFFFFFF;
	}

	private void setColor(int rgb) {
		Config cfg = Config.get();
		rgb &= 0xFFFFFF;
		switch (tab) {
			case TAB_BG -> { cfg.background = rgb; cfg.matchCover = false; }
			case TAB_TEXT -> cfg.textColor = rgb;
			default -> { cfg.accent = rgb; cfg.matchCover = false; }
		}
		cfg.save();
	}

	private void refreshHexField() {
		updatingField = true;
		hexField.setText(String.format("#%06X", currentColor()));
		updatingField = false;
	}

	@Override
	protected void init() {
		int cx = this.width / 2;
		top = Math.min(this.height / 2 + 10, this.height - 204);

		addDrawableChild(ButtonWidget.builder(Text.literal("Hide / show bar"), b -> {
			Config.get().visible = !Config.get().visible;
			Config.get().save();
		}).dimensions(cx - 120, top, 118, 20).build());

		addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> close())
				.dimensions(cx + 2, top, 118, 20).build());

		matchButton = ButtonWidget.builder(Text.literal("Match cover"), b -> {
			Config.get().matchCover = !Config.get().matchCover;
			Config.get().save();
		}).dimensions(cx - 120, top + 24, 118, 20).build();
		addDrawableChild(matchButton);

		glowButton = ButtonWidget.builder(Text.literal("Cover glow"), b -> {
			Config.get().glow = !Config.get().glow;
			Config.get().save();
		}).dimensions(cx + 2, top + 24, 118, 20).build();
		addDrawableChild(glowButton);

		shapeButton = ButtonWidget.builder(Text.literal("Shape"), b -> {
			Config.get().shape = (Config.get().shape + 1) % SpotifyWidget.SHAPE_NAMES.length;
			Config.get().save();
		}).dimensions(cx - 120, top + 48, 240, 20).build();
		addDrawableChild(shapeButton);

		for (int i = 0; i < 3; i++) {
			final int idx = i;
			tabButtons[i] = ButtonWidget.builder(Text.literal(TAB_NAMES[i]), b -> {
				tab = idx;
				refreshHexField();
			}).dimensions(cx - 119 + i * 80, top + 72, 78, 18).build();
			addDrawableChild(tabButtons[i]);
		}

		hexField = new TextFieldWidget(this.textRenderer, cx - 40, top + 96 + ROWS * (SW + SW_GAP) + 4,
				80, 18, Text.literal("Hex"));
		hexField.setMaxLength(7);
		hexField.setPlaceholder(Text.literal("#RRGGBB"));
		hexField.setChangedListener(s -> {
			if (updatingField) return;
			String h = s.startsWith("#") ? s.substring(1) : s;
			if (h.length() != 6) return;
			try {
				setColor(Integer.parseInt(h, 16));
			} catch (NumberFormatException ignored) {
				// not a valid hex color yet
			}
		});
		addDrawableChild(hexField);
		refreshHexField();
	}

	private int gridX() {
		return this.width / 2 - (COLS * SW + (COLS - 1) * SW_GAP) / 2;
	}

	private int gridY() {
		return top + 96;
	}

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		ctx.fill(0, 0, this.width, this.height, 0x66000000);

		SpotifyClient sp = SpotifyBarClient.spotify;
		Config cfg = Config.get();
		clamp(cfg);
		matchButton.setMessage(Text.literal("Match cover: " + (cfg.matchCover ? "ON" : "OFF")));
		glowButton.setMessage(Text.literal("Glow: " + (cfg.glow ? "ON" : "OFF")));
		shapeButton.setMessage(Text.literal("Card shape: " + SpotifyWidget.SHAPE_NAMES[cfg.shape % SpotifyWidget.SHAPE_NAMES.length]));
		for (int i = 0; i < 3; i++) tabButtons[i].active = i != tab;

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
				"Drag the card to move it. Click its time bar to sync the time.", this.width / 2, top - 14, 0xFFFFFFFF);

		// palette grid
		int[] pal = palette();
		int cur = currentColor();
		for (int i = 0; i < pal.length; i++) {
			int sx = gridX() + (i % COLS) * (SW + SW_GAP);
			int sy = gridY() + (i / COLS) * (SW + SW_GAP);
			boolean sel = (pal[i] & 0xFFFFFF) == cur;
			ctx.fill(sx - 1, sy - 1, sx + SW + 1, sy + SW + 1, sel ? 0xFFFFFFFF : 0xFF000000);
			ctx.fill(sx, sy, sx + SW, sy + SW, 0xFF000000 | pal[i]);
		}

		// preview of the color being edited, left of the hex field
		int px = this.width / 2 - 40 - SW - 6;
		int py = gridY() + ROWS * (SW + SW_GAP) + 6;
		ctx.fill(px - 1, py - 1, px + SW + 1, py + SW + 1, 0xFFFFFFFF);
		ctx.fill(px, py, px + SW, py + SW, 0xFF000000 | cur);

		super.render(ctx, mouseX, mouseY, delta);
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

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		if (super.mouseClicked(mx, my, button)) return true;
		if (button != 0) return false;

		Config cfg = Config.get();
		SpotifyClient sp = SpotifyBarClient.spotify;

		// palette grid
		int gx = (int) mx - gridX();
		int gy = (int) my - gridY();
		if (gx >= 0 && gy >= 0) {
			int col = gx / (SW + SW_GAP), row = gy / (SW + SW_GAP);
			if (col < COLS && row < ROWS && gx % (SW + SW_GAP) < SW && gy % (SW + SW_GAP) < SW) {
				setColor(palette()[row * COLS + col]);
				refreshHexField();
				return true;
			}
		}

		// click the time bar to correct the elapsed time
		if (sp.running && sp.hasTrack && sp.durationMs > 0) {
			int[] bar = SpotifyWidget.barRect(cfg.x, cfg.y);
			if (mx >= bar[0] && mx <= bar[0] + bar[2] && my >= bar[1] - 4 && my <= bar[1] + 7) {
				sp.seekTo((long) ((mx - bar[0]) / bar[2] * sp.durationMs));
				return true;
			}
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
