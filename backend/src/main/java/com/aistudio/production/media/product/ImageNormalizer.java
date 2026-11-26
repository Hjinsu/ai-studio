package com.aistudio.production.media.product;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/**
 * D01 image normalisation to the exact 1080x1920 9:16 canvas. Aspect-ratio mismatches use fit+pad by default; an
 * in-content crop is used only when it removes at most 10% per axis. Stretching is impossible by construction and
 * the effective upscale (fit scale x maximum camera zoom) is limited: above 1.5 warns, above 2.0 blocks.
 */
final class ImageNormalizer {
    static final int WIDTH = 1080;
    static final int HEIGHT = 1920;
    static final long MAX_BYTES = 16L * 1024 * 1024;
    static final long MAX_PIXELS = 16_000_000L;
    static final double MAX_CROP = 0.10;

    record Report(int sourceWidth, int sourceHeight, int orientation, String mode, double cropX, double cropY,
            int contentX, int contentY, int contentWidth, int contentHeight, double fitScale, double maxZoom,
            double effectiveScale, double paddedAreaRatio, List<String> warnings) {}

    record Normalized(byte[] png, Report report) {}

    private ImageNormalizer() {}

    static Normalized normalize(byte[] source, String mimeType, double maxZoom) {
        if (source.length == 0 || source.length > MAX_BYTES) {
            throw MediaFailure.blocked("MEDIA_IMAGE_INVALID", "Image exceeds 16 MiB");
        }
        boolean png = source.length > 8 && (source[0] & 0xFF) == 0x89 && source[1] == 'P' && source[2] == 'N'
                && source[3] == 'G' && source[4] == 0x0D && source[5] == 0x0A && source[6] == 0x1A && source[7] == 0x0A;
        boolean jpeg = source.length > 3 && (source[0] & 0xFF) == 0xFF && (source[1] & 0xFF) == 0xD8
                && (source[2] & 0xFF) == 0xFF;
        if (!(png && "image/png".equals(mimeType)) && !(jpeg && "image/jpeg".equals(mimeType))) {
            throw MediaFailure.blocked("MEDIA_IMAGE_INVALID", "Image magic bytes do not match the declared type");
        }
        BufferedImage decoded = decode(source, png ? "png" : "jpeg");
        int orientation = jpeg ? Exif.orientation(source) : 1;
        BufferedImage oriented = orient(decoded, orientation);
        int w = oriented.getWidth();
        int h = oriented.getHeight();
        if (Math.min(w, h) < 720 || Math.max(w, h) < 1280) {
            throw MediaFailure.blocked("MEDIA_IMAGE_TOO_SMALL", "Image is below the 720x1280 minimum");
        }
        List<String> warnings = new ArrayList<>();
        double target = (double) WIDTH / HEIGHT;
        double ratio = (double) w / h;
        int cropW = w;
        int cropH = h;
        String mode;
        if (ratio > target) {
            int needW = (int) Math.round(h * target);
            double fraction = 1 - (double) needW / w;
            if (fraction <= MAX_CROP) {
                cropW = needW;
            }
        } else if (ratio < target) {
            int needH = (int) Math.round(w / target);
            double fraction = 1 - (double) needH / h;
            if (fraction <= MAX_CROP) {
                cropH = needH;
            }
        }
        double cropX = 1 - (double) cropW / w;
        double cropY = 1 - (double) cropH / h;
        int x0 = (w - cropW) / 2;
        int y0 = (h - cropH) / 2;
        double scale = Math.min((double) WIDTH / cropW, (double) HEIGHT / cropH);
        int contentW = Math.min(WIDTH, (int) Math.round(cropW * scale));
        int contentH = Math.min(HEIGHT, (int) Math.round(cropH * scale));
        if (contentW == WIDTH && contentH == HEIGHT) {
            mode = cropX > 0 || cropY > 0 ? "CROP" : "SCALE";
        } else {
            mode = cropX > 0 || cropY > 0 ? "CROP_FIT_PAD" : "FIT_PAD";
        }
        int contentX = (WIDTH - contentW) / 2;
        int contentY = (HEIGHT - contentH) / 2;
        double effective = scale * maxZoom;
        if (effective > 2.0) {
            throw MediaFailure.blocked("MEDIA_IMAGE_UPSCALE", "Effective upscale exceeds 2.0");
        }
        if (effective > 1.5) {
            warnings.add("EFFECTIVE_UPSCALE_ABOVE_1_5");
        }
        double padded = 1 - (double) contentW * contentH / ((double) WIDTH * HEIGHT);
        if (padded > 0.25) {
            warnings.add("PADDING_ABOVE_25_PERCENT");
        }
        BufferedImage cropped = oriented.getSubimage(x0, y0, cropW, cropH);
        BufferedImage content = resize(cropped, contentW, contentH);
        BufferedImage canvas = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas.createGraphics();
        try {
            g.setColor(Color.BLACK);
            g.fillRect(0, 0, WIDTH, HEIGHT);
            g.drawImage(content, contentX, contentY, null);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(4 * 1024 * 1024);
        try {
            if (!ImageIO.write(canvas, "png", out)) {
                throw new IOException("No PNG writer");
            }
        } catch (IOException ex) {
            throw MediaFailure.retry("MEDIA_IMAGE_ENCODE", "PNG encoding failed", ex);
        }
        if (out.size() > MAX_BYTES) {
            throw MediaFailure.blocked("MEDIA_IMAGE_INVALID", "Normalised image exceeds 16 MiB");
        }
        return new Normalized(out.toByteArray(), new Report(w, h, orientation, mode, round(cropX), round(cropY),
                contentX, contentY, contentW, contentH, round(scale), maxZoom, round(effective), round(padded),
                List.copyOf(warnings)));
    }

    private static BufferedImage decode(byte[] source, String format) {
        try (ImageInputStream stream = ImageIO.createImageInputStream(new ByteArrayInputStream(source))) {
            Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName(format);
            if (stream == null || !readers.hasNext()) {
                throw MediaFailure.blocked("MEDIA_IMAGE_INVALID", "No image decoder");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream, false, true);
                if (reader.getNumImages(true) != 1) {
                    throw MediaFailure.blocked("MEDIA_IMAGE_INVALID", "Image must contain exactly one frame");
                }
                long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
                if (pixels > MAX_PIXELS) {
                    throw MediaFailure.blocked("MEDIA_IMAGE_INVALID", "Image exceeds 16 MP");
                }
                BufferedImage image = reader.read(0);
                BufferedImage rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
                Graphics2D g = rgb.createGraphics();
                try {
                    g.setColor(Color.BLACK);
                    g.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
                    g.drawImage(image, 0, 0, null);
                } finally {
                    g.dispose();
                }
                return rgb;
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException ex) {
            if (ex instanceof MediaFailure failure) {
                throw failure;
            }
            throw MediaFailure.blocked("MEDIA_IMAGE_INVALID", "Image cannot be decoded");
        }
    }

    static BufferedImage orient(BufferedImage image, int orientation) {
        if (orientation <= 1 || orientation > 8) {
            return image;
        }
        int w = image.getWidth();
        int h = image.getHeight();
        boolean swap = orientation >= 5;
        AffineTransform t = new AffineTransform();
        switch (orientation) {
            case 2 -> { t.translate(w, 0); t.scale(-1, 1); }
            case 3 -> { t.translate(w, h); t.rotate(Math.PI); }
            case 4 -> { t.translate(0, h); t.scale(1, -1); }
            case 5 -> { t.rotate(-Math.PI / 2); t.scale(-1, 1); }
            case 6 -> { t.translate(h, 0); t.rotate(Math.PI / 2); }
            case 7 -> { t.scale(-1, 1); t.translate(-h, 0); t.translate(0, w); t.rotate(3 * Math.PI / 2); }
            case 8 -> { t.translate(0, w); t.rotate(3 * Math.PI / 2); }
            default -> { }
        }
        BufferedImage out = new BufferedImage(swap ? h : w, swap ? w : h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.drawImage(image, t, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    /** High-quality resize: repeated halving for large reductions, then bicubic to the exact size. */
    static BufferedImage resize(BufferedImage image, int width, int height) {
        BufferedImage current = image;
        while (current.getWidth() / 2 >= width && current.getHeight() / 2 >= height) {
            current = draw(current, current.getWidth() / 2, current.getHeight() / 2);
        }
        return current.getWidth() == width && current.getHeight() == height ? current : draw(current, width, height);
    }

    private static BufferedImage draw(BufferedImage image, int width, int height) {
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(image, 0, 0, width, height, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private static double round(double value) {
        return Math.round(value * 10_000d) / 10_000d;
    }

    /** Reads only the EXIF orientation tag (0x0112) of a JPEG APP1 segment. */
    static final class Exif {
        private Exif() {}

        static int orientation(byte[] jpeg) {
            int i = 2;
            while (i + 4 <= jpeg.length && (jpeg[i] & 0xFF) == 0xFF) {
                int marker = jpeg[i + 1] & 0xFF;
                int length = ((jpeg[i + 2] & 0xFF) << 8) | (jpeg[i + 3] & 0xFF);
                if (marker == 0xDA || length < 2 || i + 2 + length > jpeg.length) {
                    return 1;
                }
                if (marker == 0xE1 && length > 14 && jpeg[i + 4] == 'E' && jpeg[i + 5] == 'x' && jpeg[i + 6] == 'i'
                        && jpeg[i + 7] == 'f') {
                    return tiffOrientation(jpeg, i + 10, i + 2 + length);
                }
                i += 2 + length;
            }
            return 1;
        }

        private static int tiffOrientation(byte[] b, int tiff, int end) {
            if (tiff + 8 > end) {
                return 1;
            }
            boolean little = b[tiff] == 'I' && b[tiff + 1] == 'I';
            int ifd = tiff + (int) u32(b, tiff + 4, little);
            if (ifd + 2 > end || ifd < tiff) {
                return 1;
            }
            int entries = u16(b, ifd, little);
            for (int e = 0; e < entries; e++) {
                int entry = ifd + 2 + e * 12;
                if (entry + 12 > end) {
                    return 1;
                }
                if (u16(b, entry, little) == 0x0112) {
                    int value = u16(b, entry + 8, little);
                    return value >= 1 && value <= 8 ? value : 1;
                }
            }
            return 1;
        }

        private static int u16(byte[] b, int at, boolean little) {
            return little ? (b[at] & 0xFF) | ((b[at + 1] & 0xFF) << 8) : ((b[at] & 0xFF) << 8) | (b[at + 1] & 0xFF);
        }

        private static long u32(byte[] b, int at, boolean little) {
            return little ? (u16(b, at, true) | ((long) u16(b, at + 2, true) << 16))
                    : (((long) u16(b, at, false) << 16) | u16(b, at + 2, false));
        }
    }
}
