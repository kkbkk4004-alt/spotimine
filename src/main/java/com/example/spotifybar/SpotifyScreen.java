package com.example.spotifybar;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

/**
 * Opened with the keybind (default J). Drag the card anywhere, use the
 * controls under it, paste your Client ID and log in.
 */
public class SpotifyScreen extends Screen {
	private static final int BTN_W = 30;
	private static final int BTN_H = 16;

	private boolean dragging = false;
	private int dragDX, dragDY;

	private TextFieldWidget clientIdField;
	private ButtonWidget loginButton;
	private ButtonWidget logoutButton;

	public SpotifyScreen() {
		super(Text.literal("Spotify Bar"));
	}

	@Override
	protected void init() {
		int cx = this.width / 2;
		int top = this.height / 2 + 10;

		clientIdField = new TextFieldWidget(this.textRenderer, cx - 120, top, 240, 18, Text.literal("Client ID"));
		clientIdField.setMaxLength(64);
		clientIdField.setPlaceholder(Text.literal("Paste your Spotify Client ID here"));
		clientIdField.setText(Config.get().clientId);
		clientIdField.setChangedListener(s -> {
			Config.get().clientId = s.trim();
			Config.get().save();
		});
		addDrawableChild(clientIdField);

		loginButton = ButtonWidget.builder(Text.literal("Log in with Spotify"), b -> SpotifyBarClient.spotify.login())
				.dimensions(cx - 120, top + 24, 118, 20).build();
		addDrawableChild(loginButton);

		logoutButton = ButtonWidget.builder(Text.literal("Log out"), b -> SpotifyBarClient.spotify.logout())
				.dimensions(cx + 2, top + 24, 118, 20).build();
		addDrawableChild(logoutButton);

		addDrawableChild(ButtonWidget.builder(Text.literal("Hide / show bar"), b -> {
			Config.get().visible = !Config.get().visible;
			Config.get().save();
		}).dimensions(cx - 120, top + 48, 118, 20).build());

		addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> close())
				.dimensions(cx + 2, top + 48, 118, 20).build());
	}

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		ctx.fill(0, 0, this.width, this.height, 0x66000000);

		SpotifyClient sp = SpotifyBarClient.spotify;
		loginButton.active = !sp.loggedIn && !sp.loggingIn;
		logoutButton.active = sp.loggedIn;
		clientIdField.setEditable(!sp.loggedIn);

		Config cfg = Config.get();
		clamp(cfg);
		SpotifyWidget.draw(ctx, cfg.x, cfg.y);

		// controls under the card
		if (sp.loggedIn && sp.hasTrack) {
			int cy = cfg.y + SpotifyWidget.HEIGHT + 3;
			int total = BTN_W * 3 + 4;
			int bx = cfg.x + (SpotifyWidget.WIDTH - total) / 2;
			drawButton(ctx, bx, cy, "|<", mouseX, mouseY);
			drawButton(ctx, bx + BTN_W + 2, cy, sp.playing ? "||" : ">", mouseX, mouseY);
			drawButton(ctx, bx + (BTN_W + 2) * 2, cy, ">|", mouseX, mouseY);
		}

		ctx.drawCenteredTextWithShadow(this.textRenderer,
				"Drag the card to move it", this.width / 2, this.height / 2 - 10, 0xFFFFFFFF);
		if (!sp.status.isEmpty()) {
			ctx.drawCenteredTextWithShadow(this.textRenderer, sp.status,
					this.width / 2, this.height / 2 + 82, 0xFFFFAA55);
		}

		super.render(ctx, mouseX, mouseY, delta);
	}

	private void drawButton(DrawContext ctx, int x, int y, String label, int mx, int my) {
		boolean hover = mx >= x && mx < x + BTN_W && my >= y && my < y + BTN_H;
		ctx.fill(x, y, x + BTN_W, y + BTN_H, hover ? 0xFF1DB954 : 0xFF2A2A2A);
		ctx.drawCenteredTextWithShadow(this.textRenderer, label, x + BTN_W / 2, y + 4, 0xFFFFFFFF);
	}

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		if (super.mouseClicked(mx, my, button)) return true;
		if (button != 0) return false;

		Config cfg = Config.get();
		SpotifyClient sp = SpotifyBarClient.spotify;

		if (sp.loggedIn && sp.hasTrack) {
			int cy = cfg.y + SpotifyWidget.HEIGHT + 3;
			int total = BTN_W * 3 + 4;
			int bx = cfg.x + (SpotifyWidget.WIDTH - total) / 2;
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
