package com.example.spotifybar;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

/**
 * Decodes JPEG/PNG with Java's ImageIO. Kept in its own class so the mod still
 * loads if the game's Java runtime has no java.desktop module.
 */
public final class ImageFallback {
	private ImageFallback() {}

	public record Pixels(int[] argb, int width, int height) {}

	public static Pixels decode(byte[] data) throws Exception {
		BufferedImage img = ImageIO.read(new ByteArrayInputStream(data));
		if (img == null) return null;
		int w = img.getWidth(), h = img.getHeight();
		return new Pixels(img.getRGB(0, 0, w, h, null, 0, w), w, h);
	}
}
