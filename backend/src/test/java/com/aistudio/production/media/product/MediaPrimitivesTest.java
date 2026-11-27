package com.aistudio.production.media.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

/** D01/D02 primitives: image normalisation, WAV decoding, 48 kHz resampling and the frame timeline. */
class MediaPrimitivesTest {
    static byte[] image(int w, int h, String format) throws Exception {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.ORANGE);
        g.fillRect(0, 0, w, h);
        g.setColor(Color.BLUE);
        g.fillRect(0, 0, w / 2, h / 3);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, format, out);
        return out.toByteArray();
    }

    static BufferedImage decode(byte[] png) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(png));
    }

    @Test
    void largerPortraitImageScalesDownWithoutCropOrPadding() throws Exception {
        var result = ImageNormalizer.normalize(image(1440, 2560, "png"), "image/png", RenderPlan.MAX_ZOOM);
        var out = decode(result.png());
        assertThat(out.getWidth()).isEqualTo(1080);
        assertThat(out.getHeight()).isEqualTo(1920);
        assertThat(result.report().mode()).isEqualTo("SCALE");
        assertThat(result.report().effectiveScale()).isEqualTo(0.885);
        assertThat(result.report().warnings()).isEmpty();
    }

    @Test
    void nearAspectImageIsCroppedAtMostTenPercentPerAxis() throws Exception {
        var result = ImageNormalizer.normalize(image(1200, 1920, "png"), "image/png", 1.0);
        assertThat(result.report().mode()).isEqualTo("CROP");
        assertThat(result.report().cropX()).isEqualTo(0.1);
        assertThat(result.report().paddedAreaRatio()).isZero();
    }

    @Test
    void landscapeImageIsFittedAndPaddedWithAWarningInsteadOfStretched() throws Exception {
        var result = ImageNormalizer.normalize(image(1920, 1080, "jpeg"), "image/jpeg", 1.0);
        var out = decode(result.png());
        assertThat(result.report().mode()).isEqualTo("FIT_PAD");
        assertThat(result.report().contentWidth()).isEqualTo(1080);
        assertThat(result.report().contentHeight()).isEqualTo(608);
        assertThat(result.report().warnings()).contains("PADDING_ABOVE_25_PERCENT");
        assertThat(out.getRGB(540, 10) & 0xFFFFFF).isZero();
    }

    @Test
    void upscaleAboveOneAndAHalfWarnsAndAboveTwoBlocks() throws Exception {
        var minimum = ImageNormalizer.normalize(image(720, 1280, "png"), "image/png", RenderPlan.MAX_ZOOM);
        assertThat(minimum.report().effectiveScale()).isGreaterThan(1.5);
        assertThat(minimum.report().warnings()).contains("EFFECTIVE_UPSCALE_ABOVE_1_5");
        assertThatThrownBy(() -> ImageNormalizer.normalize(image(720, 1280, "png"), "image/png", 1.4))
                .isInstanceOfSatisfying(MediaFailure.class, f -> assertThat(f.code()).isEqualTo("MEDIA_IMAGE_UPSCALE"));
        assertThatThrownBy(() -> ImageNormalizer.normalize(image(600, 1100, "png"), "image/png", 1.0))
                .isInstanceOfSatisfying(MediaFailure.class, f -> assertThat(f.code()).isEqualTo("MEDIA_IMAGE_TOO_SMALL"));
    }

    @Test
    void declaredTypeMustMatchMagicBytesAndDecode() throws Exception {
        byte[] png = image(1080, 1920, "png");
        assertThatThrownBy(() -> ImageNormalizer.normalize(png, "image/jpeg", 1.0))
                .isInstanceOfSatisfying(MediaFailure.class, f -> assertThat(f.code()).isEqualTo("MEDIA_IMAGE_INVALID"));
        byte[] truncated = java.util.Arrays.copyOf(png, 64);
        assertThatThrownBy(() -> ImageNormalizer.normalize(truncated, "image/png", 1.0))
                .isInstanceOfSatisfying(MediaFailure.class, f -> assertThat(f.code()).isEqualTo("MEDIA_IMAGE_INVALID"));
    }

    @Test
    void exifOrientationIsAppliedBeforeMeasuringThePixelSize() throws Exception {
        byte[] jpeg = image(1920, 1080, "jpeg");
        // APP1 Exif, big-endian TIFF, one IFD entry: Orientation (0x0112) = 6 (rotate 90 degrees clockwise).
        byte[] app1 = {(byte) 0xFF, (byte) 0xE1, 0, 34, 'E', 'x', 'i', 'f', 0, 0, 'M', 'M', 0, 42, 0, 0, 0, 8, 0, 1,
                0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, 6, 0, 0, 0, 0, 0, 0};
        byte[] rotated = new byte[jpeg.length + app1.length];
        int at = (jpeg[2] & 0xFF) == 0xFF && (jpeg[3] & 0xFF) == 0xE0 ? 4 + (((jpeg[4] & 0xFF) << 8) | (jpeg[5] & 0xFF)) : 2;
        System.arraycopy(jpeg, 0, rotated, 0, at);
        System.arraycopy(app1, 0, rotated, at, app1.length);
        System.arraycopy(jpeg, at, rotated, at + app1.length, jpeg.length - at);
        assertThat(ImageNormalizer.Exif.orientation(rotated)).isEqualTo(6);
        var result = ImageNormalizer.normalize(rotated, "image/jpeg", 1.0);
        assertThat(result.report().sourceWidth()).isEqualTo(1080);
        assertThat(result.report().sourceHeight()).isEqualTo(1920);
        assertThat(result.report().mode()).isEqualTo("SCALE");
    }

    static byte[] wav(int rate, int channels, short[] samples, boolean extendedHeader) {
        int fmt = extendedHeader ? 18 : 16;
        ByteBuffer b = ByteBuffer.allocate(20 + fmt + 8 + samples.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0x46464952).putInt(b.capacity() - 8).putInt(0x45564157).putInt(0x20746d66).putInt(fmt)
                .putShort((short) 1).putShort((short) channels).putInt(rate).putInt(rate * channels * 2)
                .putShort((short) (channels * 2)).putShort((short) 16);
        if (extendedHeader) {
            b.putShort((short) 0);
        }
        b.putInt(0x61746164).putInt(samples.length * 2);
        for (short s : samples) {
            b.putShort(s);
        }
        return b.array();
    }

    @Test
    void wavSampleCountComesFromTheDataChunkNotTheFileSize() {
        var wav = Wav.parse(wav(22050, 1, new short[] {1, 2, 3, 4, 5}, true));
        assertThat(wav.frames()).isEqualTo(5);
        assertThat(wav.sampleRate()).isEqualTo(22050);
        assertThat(Wav.parse(new Wav(48000, 1, new short[] {7, -7}).encode()).samples()).containsExactly(7, -7);
        assertThatThrownBy(() -> Wav.parse(new byte[] {'R', 'I', 'F', 'F'}))
                .isInstanceOfSatisfying(MediaFailure.class, f -> assertThat(f.code()).isEqualTo("MEDIA_AUDIO_INVALID"));
    }

    @Test
    void resamplerMapsTheSampleClockAndPreservesPitch() {
        int in = 22050;
        short[] tone = new short[in];
        for (int i = 0; i < tone.length; i++) {
            tone[i] = (short) Math.round(12000 * Math.sin(2 * Math.PI * 440 * i / in));
        }
        short[] out = Resampler.resample(tone, in, 48000);
        assertThat(out.length).isEqualTo(48000);
        assertThat(Resampler.map(22050, in, 48000)).isEqualTo(48000);
        assertThat(Resampler.map(1, in, 48000)).isEqualTo(2);
        int crossings = 0;
        for (int i = 1000; i < 47000; i++) {
            if (out[i - 1] < 0 && out[i] >= 0) {
                crossings++;
            }
        }
        assertThat(crossings).isBetween(420, 423);
    }

    @Test
    void timelineRoundsBoundariesToFramesAndCoversTheLastSample() {
        var timeline = Timeline.of(List.of("a", "b", "c"), List.of(48_001L, 800L * 3 + 799, 1_000_000L));
        assertThat(timeline.scenes().get(0).startFrame()).isZero();
        assertThat(timeline.scenes().get(1).startFrame()).isEqualTo(30);
        assertThat(timeline.scenes().get(2).startFrame()).isEqualTo(Timeline.frame(48_001L + 3199));
        long total = 48_001L + 3199 + 1_000_000L;
        assertThat(timeline.totalFrames()).isEqualTo((int) ((total + 1599) / 1600));
        assertThat(timeline.outputSamples()).isGreaterThanOrEqualTo(total);
        assertThat(timeline.narrationInRange()).isFalse();
        assertThat(Timeline.of(List.of("x"), List.of(45L * 48000)).narrationInRange()).isTrue();
        assertThatThrownBy(() -> Timeline.of(List.of("x", "y"), List.of(10L, 10L)))
                .isInstanceOfSatisfying(MediaFailure.class, f -> assertThat(f.code()).isEqualTo("MEDIA_TIMELINE_INVALID"));
    }
}
