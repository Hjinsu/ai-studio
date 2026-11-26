import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.MultipleGradientPaint;
import java.awt.Polygon;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.font.GlyphVector;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;
import java.io.File;
import java.util.Random;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

/**
 * V2-04 acceptance image fixtures, drawn procedurally with Java2D (no third-party artwork). The only external
 * input is the SIL OFL 1.1 Noto Sans KR Bold font, used to draw question-mark glyphs.
 *
 * Usage: java GenerateImages.java <output-dir> <NotoSansKR-Bold.otf>
 */
public class GenerateImages {
    static Font font;

    public static void main(String[] args) throws Exception {
        File out = new File(args[0]);
        out.mkdirs();
        font = Font.createFont(Font.TRUETYPE_FONT, new File(args[1]));
        png(neutronCube(), new File(out, "neutron-1.png"));
        png(supernova(), new File(out, "neutron-2.png"));
        jpeg(compression(), new File(out, "neutron-3.jpg"));
        png(burst(), new File(out, "neutron-4.png"));
        png(crossSection(), new File(out, "neutron-5.png"));
        png(venusSplit(), new File(out, "venus-1.png"));
        png(venusHorizon(), new File(out, "venus-2.png"));
        png(venusImpact(), new File(out, "venus-3.png"));
    }

    // ------------------------------------------------------------------ helpers

    static Graphics2D canvas(BufferedImage image) {
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        return g;
    }

    static void vertical(Graphics2D g, int w, int h, Color top, Color bottom) {
        g.setPaint(new GradientPaint(0, 0, top, 0, h, bottom));
        g.fillRect(0, 0, w, h);
    }

    static void glow(Graphics2D g, double cx, double cy, double r, Color color, float alpha) {
        Color c0 = new Color(color.getRed(), color.getGreen(), color.getBlue(), Math.round(255 * alpha));
        Color c1 = new Color(color.getRed(), color.getGreen(), color.getBlue(), 0);
        g.setPaint(new RadialGradientPaint(new Point2D.Double(cx, cy), (float) r, new float[] {0f, 1f},
                new Color[] {c0, c1}));
        g.fill(new Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r));
    }

    static void stars(Graphics2D g, int w, int h, long seed, int count) {
        Random random = new Random(seed);
        for (int i = 0; i < count; i++) {
            double x = random.nextDouble() * w;
            double y = random.nextDouble() * h;
            double size = Math.pow(random.nextDouble(), 6) * 4 + 0.6;
            int a = 90 + random.nextInt(160);
            int tint = random.nextInt(3);
            Color c = tint == 0 ? new Color(255, 236, 214, a) : tint == 1 ? new Color(210, 225, 255, a)
                    : new Color(255, 255, 255, a);
            g.setColor(c);
            g.fill(new Ellipse2D.Double(x - size / 2, y - size / 2, size, size));
            if (size > 3.2) {
                glow(g, x, y, size * 5, c, 0.25f);
            }
        }
    }

    static void vignette(Graphics2D g, int w, int h, float strength) {
        g.setPaint(new RadialGradientPaint(new Point2D.Double(w / 2.0, h / 2.0), (float) (Math.hypot(w, h) / 1.7),
                new float[] {0.55f, 1f}, new Color[] {new Color(0, 0, 0, 0), new Color(0, 0, 0, Math.round(255 * strength))}));
        g.fillRect(0, 0, w, h);
    }

    static BufferedImage blur(BufferedImage image, int radius) {
        int size = radius * 2 + 1;
        float[] kernel = new float[size];
        double sigma = radius / 2.5;
        double sum = 0;
        for (int i = 0; i < size; i++) {
            kernel[i] = (float) Math.exp(-((i - radius) * (i - radius)) / (2 * sigma * sigma));
            sum += kernel[i];
        }
        for (int i = 0; i < size; i++) {
            kernel[i] /= sum;
        }
        BufferedImage padded = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = padded.createGraphics();
        g.drawImage(image, 0, 0, null);
        g.dispose();
        BufferedImage h = new ConvolveOp(new Kernel(size, 1, kernel), ConvolveOp.EDGE_NO_OP, null).filter(padded, null);
        return new ConvolveOp(new Kernel(1, size, kernel), ConvolveOp.EDGE_NO_OP, null).filter(h, null);
    }

    /** Draw a layer on its own transparent canvas, blur it and composite it (bloom-like soft light). */
    interface Painter { void paint(Graphics2D g); }

    static void soft(BufferedImage target, int radius, float alpha, Painter painter) {
        BufferedImage layer = new BufferedImage(target.getWidth(), target.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D lg = canvas(layer);
        painter.paint(lg);
        lg.dispose();
        int f = radius >= 16 ? 4 : 1;
        BufferedImage small = new BufferedImage(target.getWidth() / f, target.getHeight() / f, BufferedImage.TYPE_INT_ARGB);
        Graphics2D sg = small.createGraphics();
        sg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        sg.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        sg.drawImage(layer, 0, 0, small.getWidth(), small.getHeight(), null);
        sg.dispose();
        BufferedImage blurred = blur(small, Math.max(1, radius / f));
        Graphics2D g = target.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
        g.drawImage(blurred, 0, 0, target.getWidth(), target.getHeight(), null);
        g.dispose();
    }

    static void grain(BufferedImage image, long seed, int amount) {
        Random random = new Random(seed);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = image.getRGB(x, y);
                int n = (int) Math.round(random.nextGaussian() * amount);
                int r = clamp(((rgb >> 16) & 0xFF) + n);
                int gg = clamp(((rgb >> 8) & 0xFF) + n);
                int b = clamp((rgb & 0xFF) + n);
                image.setRGB(x, y, 0xFF000000 | (r << 16) | (gg << 8) | b);
            }
        }
    }

    static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    static void glyph(Graphics2D g, String text, float size, double cx, double cy, Color fill, Color edge) {
        Font f = font.deriveFont(size);
        GlyphVector vector = f.createGlyphVector(g.getFontRenderContext(), text);
        var bounds = vector.getVisualBounds();
        var shape = vector.getOutline((float) (cx - bounds.getCenterX()), (float) (cy - bounds.getCenterY()));
        if (edge != null) {
            g.setColor(edge);
            g.setStroke(new BasicStroke(size / 18f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(shape);
        }
        g.setColor(fill);
        g.fill(shape);
    }

    static void png(BufferedImage image, File file) throws Exception {
        BufferedImage rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        g.drawImage(image, 0, 0, null);
        g.dispose();
        ImageIO.write(rgb, "png", file);
    }

    static void jpeg(BufferedImage image, File file) throws Exception {
        BufferedImage rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        g.drawImage(image, 0, 0, null);
        g.dispose();
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(0.93f);
        file.delete();
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(file)) {
            writer.setOutput(stream);
            writer.write(null, new IIOImage(rgb, null, null), param);
        } finally {
            writer.dispose();
        }
    }

    // ------------------------------------------------------------------ kitchen scale + cube

    static void scale(Graphics2D g, double cx, double top, double width) {
        double h = width * 0.42;
        g.setPaint(new GradientPaint((float) cx, (float) top, new Color(38, 38, 44), (float) cx, (float) (top + h),
                new Color(10, 10, 12)));
        g.fill(new RoundRectangle2D.Double(cx - width * 0.42, top + width * 0.07, width * 0.84, h, 60, 60));
        g.setPaint(new GradientPaint((float) cx, (float) top, new Color(70, 70, 78), (float) cx,
                (float) (top + width * 0.08), new Color(22, 22, 26)));
        g.fill(new RoundRectangle2D.Double(cx - width / 2, top, width, width * 0.09, 40, 40));
        g.setColor(new Color(255, 190, 120, 70));
        g.setStroke(new BasicStroke(4f));
        g.draw(new RoundRectangle2D.Double(cx - width / 2, top, width, width * 0.09, 40, 40));
        double dw = width * 0.36;
        double dy = top + width * 0.2;
        g.setColor(new Color(6, 16, 12));
        g.fill(new RoundRectangle2D.Double(cx - dw / 2, dy, dw, width * 0.12, 18, 18));
    }

    static void readout(Graphics2D g, double cx, double y, double width, Color color, boolean blurred) {
        double digit = width * 0.075;
        for (int i = 0; i < 4; i++) {
            double x = cx - 2 * digit * 1.25 + i * digit * 1.25 + digit * 0.1;
            g.setColor(color);
            for (int s = 0; s < 3; s++) {
                g.fill(new RoundRectangle2D.Double(x, y + s * digit * 0.62, digit, digit * 0.16, 8, 8));
            }
            if (blurred) {
                g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 60));
                g.fill(new RoundRectangle2D.Double(x - digit * 0.4, y - digit * 0.2, digit * 1.8, digit * 1.7, 18, 18));
            }
        }
    }

    static void cube(Graphics2D g, double cx, double cy, double s, Color warm) {
        double dx = s * 0.86;
        double dy = s * 0.5;
        Path2D top = new Path2D.Double();
        top.moveTo(cx, cy - dy * 2);
        top.lineTo(cx + dx, cy - dy);
        top.lineTo(cx, cy);
        top.lineTo(cx - dx, cy - dy);
        top.closePath();
        Path2D left = new Path2D.Double();
        left.moveTo(cx - dx, cy - dy);
        left.lineTo(cx, cy);
        left.lineTo(cx, cy + s);
        left.lineTo(cx - dx, cy + s - dy);
        left.closePath();
        Path2D right = new Path2D.Double();
        right.moveTo(cx + dx, cy - dy);
        right.lineTo(cx, cy);
        right.lineTo(cx, cy + s);
        right.lineTo(cx + dx, cy + s - dy);
        right.closePath();
        g.setPaint(new GradientPaint((float) cx, (float) (cy - 2 * dy), new Color(255, 251, 240),
                (float) cx, (float) cy, new Color(250, 228, 196)));
        g.fill(top);
        g.setPaint(new GradientPaint((float) (cx - dx), (float) cy, new Color(214, 196, 176), (float) cx,
                (float) (cy + s), new Color(150, 128, 112)));
        g.fill(left);
        g.setPaint(new GradientPaint((float) (cx + dx), (float) cy, warm, (float) cx, (float) (cy + s),
                new Color(196, 150, 118)));
        g.fill(right);
        g.setColor(new Color(255, 255, 255, 150));
        g.setStroke(new BasicStroke((float) (s / 60), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(top);
        Random random = new Random(7);
        for (int i = 0; i < 260; i++) {
            double px = cx + (random.nextDouble() - 0.5) * dx * 1.8;
            double py = cy - dy + (random.nextDouble() - 0.3) * s * 1.2;
            g.setColor(new Color(255, 255, 255, 40 + random.nextInt(60)));
            g.fill(new Ellipse2D.Double(px, py, 3, 3));
        }
    }

    static BufferedImage neutronCube() {
        int w = 1440;
        int h = 2560;
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas(image);
        vertical(g, w, h, new Color(8, 8, 12), new Color(22, 14, 16));
        glow(g, 980, 900, 900, new Color(255, 140, 60), 0.28f);
        glow(g, 420, 1500, 700, new Color(60, 90, 160), 0.18f);
        g.dispose();
        soft(image, 40, 0.9f, l -> glow(l, w / 2.0, 1000, 420, new Color(255, 196, 120), 0.75f));
        g = canvas(image);
        scale(g, w / 2.0, 1340, 1100);
        readout(g, w / 2.0, 1340 + 1100 * 0.215, 1100, new Color(110, 255, 170, 200), false);
        cube(g, w / 2.0, 1180, 330, new Color(255, 200, 140));
        g.dispose();
        soft(image, 26, 0.65f, l -> {
            l.setColor(new Color(255, 170, 90, 200));
            l.setStroke(new BasicStroke(14f));
            l.draw(new Ellipse2D.Double(w / 2.0 - 330, 1290, 660, 110));
        });
        g = canvas(image);
        vignette(g, w, h, 0.78f);
        g.dispose();
        grain(image, 11, 4);
        return image;
    }

    // ------------------------------------------------------------------ supernova

    static BufferedImage supernova() {
        int w = 1440;
        int h = 2560;
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas(image);
        vertical(g, w, h, new Color(3, 4, 14), new Color(10, 6, 20));
        stars(g, w, h, 21, 1600);
        g.dispose();
        double cx = w / 2.0;
        double cy = 1080;
        soft(image, 60, 1f, l -> {
            glow(l, cx, cy, 980, new Color(255, 110, 30), 0.35f);
            glow(l, cx, cy, 620, new Color(255, 170, 60), 0.25f);
        });
        soft(image, 10, 1f, l -> {
            Random random = new Random(5);
            for (int i = 0; i < 5200; i++) {
                double angle = random.nextDouble() * Math.PI * 2;
                double r = 520 + random.nextGaussian() * 70 + 60 * Math.sin(angle * 7 + random.nextDouble());
                double x = cx + Math.cos(angle) * r;
                double y = cy + Math.sin(angle) * r;
                double size = 8 + random.nextDouble() * 40;
                int red = 255;
                int green = 90 + random.nextInt(120);
                int blue = 20 + random.nextInt(50);
                l.setColor(new Color(red, green, blue, 30 + random.nextInt(70)));
                l.fill(new Ellipse2D.Double(x - size / 2, y - size / 2, size, size));
            }
            for (int i = 0; i < 90; i++) {
                double angle = random.nextDouble() * Math.PI * 2;
                double r0 = 80 + random.nextDouble() * 100;
                double r1 = 420 + random.nextDouble() * 260;
                l.setColor(new Color(255, 200, 120, 40));
                l.setStroke(new BasicStroke(3f + (float) random.nextDouble() * 6f));
                l.draw(new java.awt.geom.Line2D.Double(cx + Math.cos(angle) * r0, cy + Math.sin(angle) * r0,
                        cx + Math.cos(angle) * r1, cy + Math.sin(angle) * r1));
            }
        });
        soft(image, 30, 1f, l -> {
            glow(l, cx, cy, 230, new Color(170, 210, 255), 0.9f);
            l.setColor(new Color(220, 235, 255, 200));
            l.fill(new Ellipse2D.Double(cx - 600, cy - 6, 1200, 12));
        });
        g = canvas(image);
        glow(g, cx, cy, 70, new Color(255, 255, 255), 1f);
        vignette(g, w, h, 0.7f);
        g.dispose();
        grain(image, 12, 3);
        return image;
    }

    // ------------------------------------------------------------------ compression (JPEG fixture)

    static BufferedImage compression() {
        int w = 1440;
        int h = 2560;
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas(image);
        vertical(g, w, h, new Color(6, 8, 18), new Color(18, 10, 12));
        stars(g, w, h, 33, 600);
        g.dispose();
        double cx = w / 2.0;
        double cy = 760;
        soft(image, 16, 1f, l -> {
            for (int i = 0; i < 9; i++) {
                double r = 560 - i * 55;
                l.setColor(new Color(120, 170, 255, 40 + i * 12));
                l.setStroke(new BasicStroke(10f));
                l.draw(new Arc2D.Double(cx - r, cy - r, 2 * r, 2 * r, 20 + i * 9, 300, Arc2D.OPEN));
            }
            for (int i = 0; i < 16; i++) {
                double angle = i * Math.PI / 8;
                l.setColor(new Color(160, 200, 255, 120));
                l.setStroke(new BasicStroke(8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                double r0 = 600;
                double r1 = 300;
                l.draw(new java.awt.geom.Line2D.Double(cx + Math.cos(angle) * r0, cy + Math.sin(angle) * r0,
                        cx + Math.cos(angle) * r1, cy + Math.sin(angle) * r1));
            }
        });
        soft(image, 40, 1f, l -> glow(l, cx, cy, 300, new Color(150, 200, 255), 1f));
        g = canvas(image);
        g.setPaint(new RadialGradientPaint(new Point2D.Double(cx - 40, cy - 40), 130, new float[] {0f, 1f},
                new Color[] {new Color(255, 255, 255), new Color(110, 160, 255)}));
        g.fill(new Ellipse2D.Double(cx - 120, cy - 120, 240, 240));
        scale(g, cx, 1760, 1060);
        g.dispose();
        soft(image, 18, 1f, l -> readout(l, cx, 1760 + 1060 * 0.215, 1060, new Color(255, 120, 90, 255), true));
        g = canvas(image);
        cube(g, cx, 1620, 260, new Color(190, 210, 255));
        vignette(g, w, h, 0.75f);
        g.dispose();
        grain(image, 13, 4);
        return image;
    }

    // ------------------------------------------------------------------ burst (1200x1920, needs a 10% crop)

    static BufferedImage burst() {
        int w = 1200;
        int h = 1920;
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas(image);
        vertical(g, w, h, new Color(14, 12, 18), new Color(30, 22, 20));
        Path2D bench = new Path2D.Double();
        bench.moveTo(0, 1240);
        bench.lineTo(w, 1180);
        bench.lineTo(w, h);
        bench.lineTo(0, h);
        bench.closePath();
        g.setPaint(new GradientPaint(0, 1200, new Color(70, 58, 52), 0, h, new Color(20, 16, 16)));
        g.fill(bench);
        g.setColor(new Color(255, 200, 150, 60));
        g.setStroke(new BasicStroke(5f));
        g.draw(new java.awt.geom.Line2D.Double(0, 1240, w, 1180));
        g.dispose();
        double cx = w / 2.0;
        double cy = 1060;
        soft(image, 24, 1f, l -> {
            Random random = new Random(9);
            for (int i = 0; i < 70; i++) {
                double angle = -Math.PI + random.nextDouble() * Math.PI * 1.05;
                double spread = 0.04 + random.nextDouble() * 0.05;
                double r = 500 + random.nextDouble() * 700;
                Polygon ray = new Polygon();
                ray.addPoint((int) cx, (int) cy);
                ray.addPoint((int) (cx + Math.cos(angle - spread) * r), (int) (cy + Math.sin(angle - spread) * r));
                ray.addPoint((int) (cx + Math.cos(angle + spread) * r), (int) (cy + Math.sin(angle + spread) * r));
                l.setColor(new Color(255, 220 - random.nextInt(80), 140 - random.nextInt(80), 50 + random.nextInt(60)));
                l.fill(ray);
            }
        });
        soft(image, 14, 1f, l -> {
            Random random = new Random(19);
            for (int i = 0; i < 1800; i++) {
                double angle = -Math.PI * 1.05 + random.nextDouble() * Math.PI * 1.1;
                double r = Math.abs(random.nextGaussian()) * 380;
                double size = 6 + random.nextDouble() * 34;
                l.setColor(new Color(255, 230 - random.nextInt(60), 200 - random.nextInt(120), 40 + random.nextInt(90)));
                l.fill(new Ellipse2D.Double(cx + Math.cos(angle) * r - size / 2, cy + Math.sin(angle) * r - size / 2,
                        size, size));
            }
        });
        soft(image, 50, 1f, l -> glow(l, cx, cy, 380, new Color(255, 250, 230), 1f));
        g = canvas(image);
        vignette(g, w, h, 0.65f);
        g.dispose();
        grain(image, 14, 4);
        return image;
    }

    // ------------------------------------------------------------------ cross-section

    static BufferedImage crossSection() {
        int w = 1440;
        int h = 2560;
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas(image);
        vertical(g, w, h, new Color(5, 7, 16), new Color(8, 12, 26));
        stars(g, w, h, 44, 900);
        double cx = w / 2.0;
        double cy = 1120;
        double r = 560;
        Color[] layers = {new Color(70, 84, 110), new Color(120, 104, 150), new Color(200, 120, 150),
                new Color(255, 160, 110), new Color(255, 214, 150)};
        g.setPaint(new RadialGradientPaint(new Point2D.Double(cx - 150, cy - 160), (float) (r * 1.2),
                new float[] {0f, 1f}, new Color[] {new Color(120, 140, 180), new Color(28, 34, 52)}));
        g.fill(new Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r));
        for (int i = 0; i < layers.length; i++) {
            double lr = r * (1 - i * 0.17);
            g.setColor(layers[i]);
            g.fill(new Arc2D.Double(cx - lr, cy - lr, 2 * lr, 2 * lr, -40, 110, Arc2D.PIE));
            g.setColor(new Color(0, 0, 0, 80));
            g.setStroke(new BasicStroke(3f));
            g.draw(new Arc2D.Double(cx - lr, cy - lr, 2 * lr, 2 * lr, -40, 110, Arc2D.PIE));
        }
        g.dispose();
        soft(image, 40, 1f, l -> glow(l, cx, cy, 260, new Color(255, 230, 170), 0.95f));
        g = canvas(image);
        glyph(g, "?", 520f, cx, cy - 40, new Color(255, 255, 255), new Color(120, 60, 30, 200));
        g.setColor(new Color(180, 210, 255, 120));
        g.setStroke(new BasicStroke(6f));
        g.draw(new Ellipse2D.Double(cx - r - 30, cy - r - 30, 2 * r + 60, 2 * r + 60));
        vignette(g, w, h, 0.6f);
        g.dispose();
        grain(image, 15, 3);
        return image;
    }

    // ------------------------------------------------------------------ Venus

    static void venus(Graphics2D g, double cx, double cy, double r, long seed) {
        Ellipse2D disc = new Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r);
        g.setPaint(new RadialGradientPaint(new Point2D.Double(cx - r * 0.35, cy - r * 0.35), (float) (r * 1.4),
                new float[] {0f, 0.6f, 1f}, new Color[] {new Color(255, 236, 170), new Color(232, 170, 70),
                        new Color(120, 70, 20)}));
        g.fill(disc);
        var clip = g.getClip();
        g.setClip(disc);
        Random random = new Random(seed);
        for (int i = 0; i < 26; i++) {
            double y = cy - r + random.nextDouble() * 2 * r;
            double amp = 10 + random.nextDouble() * 30;
            Path2D band = new Path2D.Double();
            band.moveTo(cx - r, y);
            for (double x = cx - r; x <= cx + r; x += 20) {
                band.lineTo(x, y + Math.sin((x - cx) / (40 + random.nextDouble() * 30)) * amp);
            }
            g.setColor(new Color(255, 230 - random.nextInt(60), 160 - random.nextInt(80), 40 + random.nextInt(50)));
            g.setStroke(new BasicStroke(12f + (float) random.nextDouble() * 30f, BasicStroke.CAP_ROUND,
                    BasicStroke.JOIN_ROUND));
            g.draw(band);
        }
        g.setPaint(new GradientPaint((float) (cx + r * 0.2), 0, new Color(0, 0, 0, 0), (float) (cx + r), 0,
                new Color(20, 8, 0, 200)));
        g.fill(disc);
        g.setClip(clip);
    }

    static BufferedImage venusSplit() {
        int w = 1440;
        int h = 2560;
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas(image);
        vertical(g, w, h, new Color(26, 14, 4), new Color(10, 6, 4));
        stars(g, w, h, 55, 500);
        g.dispose();
        soft(image, 50, 1f, l -> glow(l, 720, 760, 620, new Color(255, 170, 60), 0.35f));
        g = canvas(image);
        venus(g, 720, 760, 380, 3);
        g.setColor(new Color(255, 214, 140, 220));
        g.setStroke(new BasicStroke(10f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Arc2D.Double(720 - 470, 760 - 470, 940, 940, 90, -250, Arc2D.OPEN));
        g.fill(new Ellipse2D.Double(720 + 470 * Math.cos(Math.toRadians(-160)) - 18,
                760 - 470 * Math.sin(Math.toRadians(-160)) - 18, 36, 36));
        g.setColor(new Color(255, 214, 140, 90));
        g.setStroke(new BasicStroke(4f));
        g.draw(new java.awt.geom.Line2D.Double(140, 1330, 1300, 1330));
        g.dispose();
        soft(image, 40, 1f, l -> glow(l, 720, 1900, 260, new Color(255, 230, 140), 1f));
        g = canvas(image);
        g.setPaint(new RadialGradientPaint(new Point2D.Double(720, 1900), 120, new float[] {0f, 1f},
                new Color[] {new Color(255, 255, 230), new Color(255, 170, 40)}));
        g.fill(new Ellipse2D.Double(600, 1780, 240, 240));
        g.setColor(new Color(255, 200, 120, 160));
        g.setStroke(new BasicStroke(6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10f, new float[] {26f, 22f}, 0f));
        g.draw(new Ellipse2D.Double(720 - 520, 1900 - 300, 1040, 600));
        venus(g, 720 + 520 * Math.cos(Math.toRadians(35)), 1900 + 300 * Math.sin(Math.toRadians(35)), 52, 4);
        vignette(g, w, h, 0.6f);
        g.dispose();
        grain(image, 16, 3);
        return image;
    }

    static BufferedImage venusHorizon() {
        int w = 1440;
        int h = 2560;
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas(image);
        g.setPaint(new java.awt.LinearGradientPaint(0, 0, 0, 1500, new float[] {0f, 0.55f, 1f},
                new Color[] {new Color(90, 50, 14), new Color(214, 140, 50), new Color(255, 214, 140)},
                MultipleGradientPaint.CycleMethod.NO_CYCLE));
        g.fillRect(0, 0, w, 1500);
        g.dispose();
        soft(image, 60, 1f, l -> glow(l, 300, 1380, 560, new Color(255, 245, 210), 0.95f));
        g = canvas(image);
        g.setColor(new Color(255, 252, 235));
        g.fill(new Ellipse2D.Double(300 - 110, 1380 - 110, 220, 220));
        g.dispose();
        soft(image, 22, 1f, l -> {
            Random random = new Random(61);
            for (int i = 0; i < 40; i++) {
                double y = 300 + random.nextDouble() * 1150;
                Path2D cloud = new Path2D.Double();
                cloud.moveTo(-50, y);
                for (double x = -50; x <= w + 50; x += 30) {
                    cloud.lineTo(x, y + Math.sin(x / (90 + random.nextDouble() * 60)) * 26);
                }
                l.setColor(new Color(255, 220 - random.nextInt(60), 150 - random.nextInt(60), 30 + random.nextInt(40)));
                l.setStroke(new BasicStroke(30f + (float) random.nextDouble() * 50f));
                l.draw(cloud);
            }
        });
        g = canvas(image);
        Path2D ground = new Path2D.Double();
        ground.moveTo(0, 1500);
        Random random = new Random(62);
        for (int x = 0; x <= w; x += 40) {
            ground.lineTo(x, 1480 + random.nextInt(50) - Math.sin(x / 260.0) * 40);
        }
        ground.lineTo(w, h);
        ground.lineTo(0, h);
        ground.closePath();
        g.setPaint(new GradientPaint(0, 1450, new Color(70, 34, 12), 0, h, new Color(16, 8, 4)));
        g.fill(ground);
        double barY = 1980;
        g.setColor(new Color(255, 220, 160, 220));
        g.setStroke(new BasicStroke(14f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new java.awt.geom.Line2D.Double(260, barY, 1180, barY));
        g.setStroke(new BasicStroke(6f));
        for (int i = 0; i <= 10; i++) {
            double x = 260 + i * 92;
            g.draw(new java.awt.geom.Line2D.Double(x, barY - (i % 5 == 0 ? 40 : 22), x, barY + (i % 5 == 0 ? 40 : 22)));
        }
        for (double x : new double[] {260, 1180}) {
            g.setColor(new Color(255, 236, 170));
            g.fill(new Ellipse2D.Double(x - 40, barY - 130, 80, 80));
            g.setColor(new Color(255, 236, 170, 160));
            for (int k = 0; k < 8; k++) {
                double a = k * Math.PI / 4;
                g.draw(new java.awt.geom.Line2D.Double(x + Math.cos(a) * 55, barY - 90 + Math.sin(a) * 55,
                        x + Math.cos(a) * 75, barY - 90 + Math.sin(a) * 75));
            }
        }
        vignette(g, w, h, 0.55f);
        g.dispose();
        grain(image, 17, 4);
        return image;
    }

    static BufferedImage venusImpact() {
        int w = 1440;
        int h = 2560;
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas(image);
        vertical(g, w, h, new Color(4, 4, 12), new Color(20, 10, 6));
        stars(g, w, h, 77, 1300);
        g.dispose();
        double cx = 760;
        double cy = 1620;
        double r = 560;
        soft(image, 60, 1f, l -> glow(l, cx, cy, 900, new Color(255, 150, 50), 0.3f));
        g = canvas(image);
        venus(g, cx, cy, r, 8);
        g.dispose();
        soft(image, 20, 1f, l -> {
            l.setColor(new Color(255, 200, 120, 90));
            for (int i = 0; i < 7; i++) {
                double rr = r + 40 + i * 28;
                l.setStroke(new BasicStroke(10f - i));
                l.draw(new Arc2D.Double(cx - rr, cy - rr, 2 * rr, 2 * rr, 100 + i * 12, 120 + i * 10, Arc2D.OPEN));
            }
        });
        double ix = cx - r * 0.62;
        double iy = cy - r * 0.78;
        soft(image, 26, 1f, l -> {
            Path2D trail = new Path2D.Double();
            trail.moveTo(ix - 520, iy - 760);
            trail.lineTo(ix + 50, iy - 40);
            trail.lineTo(ix - 30, iy + 50);
            trail.closePath();
            l.setPaint(new GradientPaint((float) (ix - 520), (float) (iy - 760), new Color(255, 120, 40, 0),
                    (float) ix, (float) iy, new Color(255, 210, 120, 230)));
            l.fill(trail);
            glow(l, ix + 30, iy + 30, 260, new Color(255, 250, 220), 1f);
        });
        g = canvas(image);
        Polygon rock = new Polygon();
        Random random = new Random(88);
        for (int i = 0; i < 12; i++) {
            double a = i * Math.PI / 6;
            double rr = 70 + random.nextInt(30);
            rock.addPoint((int) (ix - 60 + Math.cos(a) * rr), (int) (iy - 70 + Math.sin(a) * rr));
        }
        g.setPaint(new GradientPaint((float) (ix - 140), (float) (iy - 150), new Color(150, 140, 130),
                (float) ix, (float) iy, new Color(50, 44, 40)));
        g.fill(rock);
        glyph(g, "?", 460f, 720, 520, new Color(255, 236, 190), new Color(90, 40, 10, 220));
        vignette(g, w, h, 0.6f);
        g.dispose();
        grain(image, 18, 3);
        return image;
    }
}
