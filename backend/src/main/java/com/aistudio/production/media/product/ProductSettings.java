package com.aistudio.production.media.product;

import java.math.BigDecimal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Deployment settings read by the product media worker. Common keys ({@code font-*}, {@code ffmpeg-sha256},
 * {@code ffprobe-sha256}) are owned by the shared media contract; the {@code product.*} keys belong to this module.
 * All values are injected by the work environment, never derived from AI output.
 */
@Component
class ProductSettings {
    private final String fixtureRoot;
    private final String fontPath;
    private final String fontSha256;
    private final String fontFamily;
    private final String ffmpegSha256;
    private final String ffprobeSha256;
    private final BigDecimal fakePriceKrw;

    ProductSettings(@Value("${ai-studio.production.media.product.fixture-root:}") String fixtureRoot,
            @Value("${ai-studio.production.media.font-path:}") String fontPath,
            @Value("${ai-studio.production.media.font-sha256:}") String fontSha256,
            @Value("${ai-studio.production.media.font-family:Noto Sans KR}") String fontFamily,
            @Value("${ai-studio.production.media.ffmpeg-sha256:}") String ffmpegSha256,
            @Value("${ai-studio.production.media.ffprobe-sha256:}") String ffprobeSha256,
            @Value("${ai-studio.production.media.product.fake-price-krw:1}") BigDecimal fakePriceKrw) {
        if (!fontFamily.matches("Noto Sans[ A-Za-z0-9]{0,40}")) {
            throw new IllegalArgumentException("Invalid media font family");
        }
        if (fakePriceKrw.signum() <= 0 || fakePriceKrw.scale() > 6 || fakePriceKrw.compareTo(new BigDecimal("1000")) > 0) {
            throw new IllegalArgumentException("Invalid FAKE media price");
        }
        this.fixtureRoot = fixtureRoot;
        this.fontPath = fontPath;
        this.fontSha256 = fontSha256;
        this.fontFamily = fontFamily;
        this.ffmpegSha256 = ffmpegSha256;
        this.ffprobeSha256 = ffprobeSha256;
        this.fakePriceKrw = fakePriceKrw;
    }

    String fixtureRoot() {
        return fixtureRoot;
    }

    String fontPath() {
        return fontPath;
    }

    String fontSha256() {
        return fontSha256;
    }

    String fontFamily() {
        return fontFamily;
    }

    String ffmpegSha256() {
        return ffmpegSha256;
    }

    String ffprobeSha256() {
        return ffprobeSha256;
    }

    /** Explicit mock price per simulated paid effect; shown as mock KRW in the manifest. */
    BigDecimal fakePriceKrw() {
        return fakePriceKrw;
    }
}
