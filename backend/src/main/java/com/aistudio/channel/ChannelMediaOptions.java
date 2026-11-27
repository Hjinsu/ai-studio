package com.aistudio.channel;

import java.util.Map;

/** Versioned channel configuration options. Legacy fixture profiles do not consume these values. */
public record ChannelMediaOptions(boolean subtitlesEnabled, String audioStyle) {
    static final String KEY = "realMedia";
    private static final ChannelMediaOptions DEFAULTS = new ChannelMediaOptions(false, "CINEMATIC_COMEDY");

    public static ChannelMediaOptions from(Map<String, Object> providerOptions) {
        if (providerOptions == null || !providerOptions.containsKey(KEY)) {
            return DEFAULTS;
        }
        if (!(providerOptions.get(KEY) instanceof Map<?, ?> media)) {
            throw new IllegalArgumentException("realMedia must be an object");
        }
        Object subtitles = media.containsKey("subtitlesEnabled") ? media.get("subtitlesEnabled") : Boolean.FALSE;
        Object audio = media.containsKey("audioStyle") ? media.get("audioStyle") : DEFAULTS.audioStyle();
        if (!(subtitles instanceof Boolean enabled) || !(audio instanceof String style) || style.isBlank()
                || style.length() > 120) {
            throw new IllegalArgumentException("Invalid subtitlesEnabled or audioStyle");
        }
        return new ChannelMediaOptions(enabled, style);
    }
}
