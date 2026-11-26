package com.aistudio.production.media.product;

import java.awt.Font;
import java.awt.font.FontRenderContext;
import java.awt.font.LineMetrics;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * D03 subtitle builder. Cues come from the verified alignment; sample offsets are quantised to the nearest frame
 * (start-inclusive, end-exclusive). ASS uses {@code floor(frame*100/30)} centiseconds and SRT {@code
 * round(frame*1000/30)} milliseconds. Text is never rewritten: line breaks replace one space, data text is escaped,
 * and only code-built tags (colour emphasis, Pop/Fade) are added. SRT is for review/download only, never burned in.
 */
record SubtitleTrack(List<Cue> cues, int fontSize, String fontFamily) {
    static final int DEFAULT_SIZE = 76;
    static final int MIN_SIZE = 60;
    static final int OUTLINE = 5;
    static final int SHADOW = 2;
    static final int SAFE_LEFT = 108;
    static final int SAFE_RIGHT = 972;
    static final int SAFE_TOP = 230;
    static final int SAFE_BOTTOM = 1536;
    static final int MARGIN_V = 400;
    static final int MIN_CUE_FRAMES = 12;
    static final int HOLD_GAP_FRAMES = 6;
    private static final Pattern NUMBER = Pattern.compile(
            "\\b\\d[\\d.,]*\\b|\\b(?:hundred|thousand|million|billion|trillion)s?\\b", Pattern.CASE_INSENSITIVE);

    enum Animation { POP, FADE }

    record Span(int start, int end) {}

    /** startFrame/endFrame are global frames; breakAt is the index of the space replaced by a line break or -1. */
    record Cue(int index, String sceneId, String segmentId, String text, int startFrame, int endFrame, int breakAt,
            List<Span> emphasis, Animation animation) {}

    record Built(SubtitleTrack track, List<String> warnings) {}

    SubtitleTrack {
        cues = List.copyOf(cues);
    }

    static Built build(List<Alignment> alignments, Timeline timeline, Font font, String family, int fontSize) {
        List<Cue> cues = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        double maxWidth = SAFE_RIGHT - SAFE_LEFT - 2.0 * OUTLINE - SHADOW;
        Font measured = scaled(font, fontSize);
        int index = 0;
        for (int s = 0; s < alignments.size(); s++) {
            var alignment = alignments.get(s);
            var span = timeline.scenes().get(s);
            List<Alignment.Cue> raw = alignment.cues();
            for (int c = 0; c < raw.size(); c++) {
                var cue = raw.get(c);
                int start = Timeline.frame(span.startSample() + cue.startSample());
                int end = Timeline.frame(span.startSample() + cue.endSample());
                if (c + 1 < raw.size()) {
                    int next = Timeline.frame(span.startSample() + raw.get(c + 1).startSample());
                    if (next > end && next - end <= HOLD_GAP_FRAMES) {
                        end = next;
                    }
                }
                end = Math.min(end, span.endFrame());
                if (end <= start) {
                    throw MediaFailure.blocked("SUBTITLE_TIMING_INVALID", "Cue has no positive length");
                }
                if (end - start < MIN_CUE_FRAMES) {
                    warnings.add("SHORT_CUE:" + (index + 1));
                }
                String segment = segmentOf(alignment, cue);
                int breakAt = wrap(cue.text(), measured, maxWidth);
                List<Span> emphasis = emphasis(cue.text(), alignment.emphasis());
                boolean question = cue.text().endsWith("?");
                Animation animation = index == 0 || question || !emphasis.isEmpty() ? Animation.POP : Animation.FADE;
                cues.add(new Cue(++index, alignment.sceneId(), segment, cue.text(), start, end, breakAt, emphasis,
                        animation));
            }
        }
        for (int i = 1; i < cues.size(); i++) {
            if (cues.get(i).startFrame() < cues.get(i - 1).endFrame()) {
                throw MediaFailure.blocked("SUBTITLE_TIMING_INVALID", "Cues overlap");
            }
        }
        return new Built(new SubtitleTrack(cues, fontSize, family), List.copyOf(warnings));
    }

    private static String segmentOf(Alignment alignment, Alignment.Cue cue) {
        for (var segment : alignment.segments()) {
            if (segment.cues().contains(cue)) {
                return segment.segmentId();
            }
        }
        throw MediaFailure.blocked("SUBTITLE_TIMING_INVALID", "Cue segment is unknown");
    }

    /**
     * libass sizes a font so ascent+descent equals the ASS font size; Java sizes the em. Scale the Java font so its
     * line height matches, then measure advances with the real font file.
     */
    static Font scaled(Font font, int assSize) {
        FontRenderContext frc = new FontRenderContext(null, true, true);
        LineMetrics metrics = font.deriveFont(100f).getLineMetrics("Hg", frc);
        float height = (metrics.getAscent() + metrics.getDescent()) / 100f;
        return font.deriveFont(assSize / height);
    }

    static double width(Font font, String text) {
        FontRenderContext frc = new FontRenderContext(null, true, true);
        return font.getStringBounds(text, frc).getWidth();
    }

    /** -1 for one line, otherwise the index of the space giving the most balanced two lines that both fit. */
    static int wrap(String text, Font font, double maxWidth) {
        if (width(font, text) <= maxWidth) {
            return -1;
        }
        int best = -2;
        double bestWidth = Double.MAX_VALUE;
        for (int i = text.indexOf(' '); i >= 0; i = text.indexOf(' ', i + 1)) {
            double w = Math.max(width(font, text.substring(0, i)), width(font, text.substring(i + 1)));
            if (w <= maxWidth && w < bestWidth) {
                best = i;
                bestWidth = w;
            }
        }
        if (best < 0) {
            throw MediaFailure.blocked("SUBTITLE_TOO_LONG", "Cue does not fit two lines");
        }
        return best;
    }

    static List<Span> emphasis(String text, List<String> phrases) {
        List<Span> spans = new ArrayList<>();
        String lower = text.toLowerCase(Locale.ROOT);
        for (String phrase : phrases) {
            String needle = phrase.toLowerCase(Locale.ROOT);
            for (int at = lower.indexOf(needle); at >= 0; at = lower.indexOf(needle, at + needle.length())) {
                spans.add(new Span(at, at + needle.length()));
            }
        }
        Matcher m = NUMBER.matcher(text);
        while (m.find()) {
            int end = m.end();
            while (end > m.start() && (text.charAt(end - 1) == '.' || text.charAt(end - 1) == ',')) {
                end--;
            }
            spans.add(new Span(m.start(), end));
        }
        spans.sort((a, b) -> a.start() != b.start() ? Integer.compare(a.start(), b.start()) : Integer.compare(b.end(), a.end()));
        List<Span> merged = new ArrayList<>();
        for (Span span : spans) {
            if (!merged.isEmpty() && span.start() <= merged.getLast().end()) {
                Span last = merged.removeLast();
                merged.add(new Span(last.start(), Math.max(last.end(), span.end())));
            } else {
                merged.add(span);
            }
        }
        return merged;
    }

    /** ASS document. {@code measure} renders outline and shadow in white so a bbox scan includes them. */
    String ass(boolean measure) {
        String outline = measure ? "&H00FFFFFF" : "&H00000000";
        String back = measure ? "&H00FFFFFF" : "&H80000000";
        StringBuilder b = new StringBuilder();
        b.append("[Script Info]\nScriptType: v4.00+\nPlayResX: 1080\nPlayResY: 1920\nWrapStyle: 2\n")
                .append("ScaledBorderAndShadow: yes\nYCbCr Matrix: TV.709\n\n[V4+ Styles]\n")
                .append("Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, ")
                .append("Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, ")
                .append("Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\n")
                .append("Style: Main,").append(fontFamily).append(',').append(fontSize)
                .append(",&H00FFFFFF,&H000000FF,").append(outline).append(',').append(back)
                .append(",-1,0,0,0,100,100,0,0,1,").append(OUTLINE).append(',').append(SHADOW).append(",2,")
                .append(SAFE_LEFT).append(',').append(1080 - SAFE_RIGHT).append(',').append(MARGIN_V).append(",1\n\n")
                .append("[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n");
        for (Cue cue : cues) {
            b.append("Dialogue: 0,").append(assTime(cue.startFrame())).append(',').append(assTime(cue.endFrame()))
                    .append(",Main,,0,0,0,,").append(cue.animation() == Animation.POP
                            ? "{\\fscx86\\fscy86\\t(0,110,\\fscx104\\fscy104)\\t(110,190,\\fscx100\\fscy100)}"
                            : "{\\fad(90,60)}")
                    .append(assText(cue)).append('\n');
        }
        return b.toString();
    }

    static String assText(Cue cue) {
        StringBuilder out = new StringBuilder();
        String text = cue.text();
        for (int i = 0; i < text.length(); i++) {
            for (Span span : cue.emphasis()) {
                if (span.start() == i) {
                    out.append("{\\c&H00D7FF&}");
                }
            }
            char ch = text.charAt(i);
            if (i == cue.breakAt()) {
                out.append("\\N");
            } else if (ch == '\\') {
                out.append("\\⁠");
            } else if (ch == '{' || ch == '}') {
                out.append('\\').append(ch);
            } else {
                out.append(ch);
            }
            for (Span span : cue.emphasis()) {
                if (span.end() == i + 1) {
                    out.append("{\\c&HFFFFFF&}");
                }
            }
        }
        return out.toString();
    }

    String srt() {
        StringBuilder b = new StringBuilder();
        for (Cue cue : cues) {
            String text = cue.breakAt() < 0 ? cue.text()
                    : cue.text().substring(0, cue.breakAt()) + "\n" + cue.text().substring(cue.breakAt() + 1);
            b.append(cue.index()).append('\n').append(srtTime(cue.startFrame())).append(" --> ")
                    .append(srtTime(cue.endFrame())).append('\n').append(text).append("\n\n");
        }
        return b.toString();
    }

    static String assTime(int frame) {
        long cs = Math.floorDiv(frame * 100L, Timeline.FPS);
        return "%d:%02d:%02d.%02d".formatted(cs / 360_000, cs / 6000 % 60, cs / 100 % 60, cs % 100);
    }

    static String srtTime(int frame) {
        long ms = Math.floorDiv(frame * 1000L * 2 + Timeline.FPS, 2L * Timeline.FPS);
        return "%02d:%02d:%02d,%03d".formatted(ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000);
    }

    /** Parses this module's SRT output back to (start ms, end ms, text with line breaks folded to spaces). */
    record ParsedCue(long startMs, long endMs, String text) {}

    static List<ParsedCue> parseSrt(String srt) {
        List<ParsedCue> out = new ArrayList<>();
        Pattern time = Pattern.compile("(\\d{2}):(\\d{2}):(\\d{2}),(\\d{3}) --> (\\d{2}):(\\d{2}):(\\d{2}),(\\d{3})");
        for (String block : srt.split("\n\n")) {
            if (block.isBlank()) {
                continue;
            }
            String[] lines = block.split("\n");
            Matcher m = time.matcher(lines.length > 1 ? lines[1] : "");
            if (lines.length < 3 || !lines[0].matches("\\d+") || !m.matches()) {
                throw MediaFailure.blocked("SUBTITLE_SRT_INVALID", "Malformed SRT block");
            }
            long start = ms(m, 1);
            long end = ms(m, 5);
            out.add(new ParsedCue(start, end, String.join(" ", java.util.Arrays.copyOfRange(lines, 2, lines.length))));
        }
        return out;
    }

    private static long ms(Matcher m, int group) {
        return Long.parseLong(m.group(group)) * 3_600_000 + Long.parseLong(m.group(group + 1)) * 60_000
                + Long.parseLong(m.group(group + 2)) * 1000 + Long.parseLong(m.group(group + 3));
    }
}
