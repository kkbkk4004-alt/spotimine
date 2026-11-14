package com.example.spotifybar;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SpotifyBarClient implements ClientModInitializer {
	public static final Logger LOGGER = LoggerFactory.getLogger("spotifybar");
	public static final SpotifyClient spotify = new SpotifyClient();

	private static KeyBinding openKey;
	private static KeyBinding toggleKey;

	@Override
	public void onInitializeClient() {
		openKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.spotifybar.open", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_J, "key.categories.spotifybar"));
		toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.spotifybar.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, "key.categories.spotifybar"));

		spotify.start();

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (openKey.wasPressed()) {
				client.setScreen(new SpotifyScreen());
			}
			while (toggleKey.wasPressed()) {
				Config.get().visible = !Config.get().visible;
				Config.get().save();
			}
		});

		HudRenderCallback.EVENT.register((ctx, tickCounter) -> {
			MinecraftClient mc = MinecraftClient.getInstance();
			if (!Config.get().visible || mc.options.hudHidden) return;
			if (mc.currentScreen instanceof SpotifyScreen) return; // the screen draws it itself

			Config cfg = Config.get();
			int x = Math.max(0, Math.min(cfg.x, ctx.getScaledWindowWidth() - SpotifyWidget.WIDTH));
			int y = Math.max(0, Math.min(cfg.y, ctx.getScaledWindowHeight() - SpotifyWidget.HEIGHT));
			SpotifyWidget.draw(ctx, x, y);
		});
	}
}
