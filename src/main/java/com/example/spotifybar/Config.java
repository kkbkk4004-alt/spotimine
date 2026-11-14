package com.example.spotifybar;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

/** Saved to .minecraft/config/spotifybar.json */
public class Config {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("spotifybar.json");

	public String clientId = "";
	/** Lets you stay logged in between launches. Treat it like a password. */
	public String refreshToken = "";
	public int x = 6;
	public int y = 6;
	public boolean visible = true;

	private static Config instance;

	public static Config get() {
		if (instance == null) instance = load();
		return instance;
	}

	private static Config load() {
		try {
			if (Files.exists(FILE)) {
				Config c = GSON.fromJson(Files.readString(FILE), Config.class);
				if (c != null) return c;
			}
		} catch (Exception e) {
			SpotifyBarClient.LOGGER.warn("Could not read config, using defaults", e);
		}
		return new Config();
	}

	public void save() {
		try {
			Files.createDirectories(FILE.getParent());
			Files.writeString(FILE, GSON.toJson(this));
		} catch (Exception e) {
			SpotifyBarClient.LOGGER.warn("Could not save config", e);
		}
	}
}
