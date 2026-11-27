package com.aistudio.production.contract;

import java.util.List;

import com.aistudio.channel.ChannelMediaOptions;

/** Request-frozen settings for the real media profile. The fixture profile remains separately decoded. */
public record RealMediaProfile(int version, long channelConfigId, String language, String voiceId,
        String audioStyle, boolean subtitlesEnabled, String priceVersion, ExecutionMode executionMode,
        List<String> approvedSkus) {
    public static final String PIPELINE = "v2-media-2";
    public enum ExecutionMode { USER_FILE_WORKER, EXPLICIT_LLM_API }

    private static final List<ProductionStage> STAGES = List.of(ProductionStage.DISCOVER, ProductionStage.SELECT,
            ProductionStage.RESEARCH, ProductionStage.SCRIPT, ProductionStage.SCENES, ProductionStage.TTS,
            ProductionStage.IMAGE, ProductionStage.SUBTITLE, ProductionStage.RENDER, ProductionStage.METADATA,
            ProductionStage.QC);

    public RealMediaProfile {
        approvedSkus = List.copyOf(approvedSkus);
        if (version != 1 || channelConfigId <= 0 || language == null || language.isBlank()
                || voiceId == null || voiceId.isBlank() || audioStyle == null || audioStyle.isBlank()
                || priceVersion == null || priceVersion.isBlank() || executionMode == null) {
            throw new IllegalArgumentException("Invalid real media profile");
        }
    }

    public static RealMediaProfile fromChannel(long configId, String language, String voiceId,
            String priceVersion, ExecutionMode mode, List<String> approvedSkus, ChannelMediaOptions options) {
        return new RealMediaProfile(1, configId, language, voiceId, options.audioStyle(), options.subtitlesEnabled(),
                priceVersion, mode, approvedSkus);
    }

    public static List<ProductionStage> order(String pipeline) {
        return PIPELINE.equals(pipeline) ? STAGES : List.of(ProductionStage.values());
    }
}
