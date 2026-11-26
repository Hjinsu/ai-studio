package com.aistudio.production.stage;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.aistudio.production.contract.ProductionPlanning.Segment;

/**
 * Rule-based estimate of English narration length (speech-estimate-1). It counts spoken tokens, expanding numbers
 * and spelled-out acronyms, at a Shorts delivery pace of about 158 words per minute, and adds short pauses at
 * commas and sentence ends. It is an estimate for planning only: the real voice length is measured in V2-04.
 */
public final class SpeechTiming {
    public static final String RULE_VERSION = "speech-estimate-1";
    static final int WORD_MS = 380;
    static final int COMMA_MS = 150;
    static final int SENTENCE_MS = 350;

    private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N}'’.,%-]*");
    private static final Pattern NUMBER = Pattern.compile("\\d[\\d,]*(?:\\.\\d+)?%?");
    private static final Pattern ACRONYM = Pattern.compile("\\p{Lu}{2,5}s?");

    private SpeechTiming() {}

    /** Estimated milliseconds for one narration text. */
    public static int estimateMs(String narration) {
        if (narration == null || narration.isBlank()) {
            return 0;
        }
        long ms = 0;
        Matcher token = TOKEN.matcher(narration);
        while (token.find()) {
            ms += (long) spokenWords(trimPunctuation(token.group())) * WORD_MS;
        }
        for (int i = 0; i < narration.length(); i++) {
            char c = narration.charAt(i);
            boolean end = c == '.' || c == '!' || c == '?';
            boolean decimal = c == '.' && i > 0 && i + 1 < narration.length()
                    && Character.isDigit(narration.charAt(i - 1)) && Character.isDigit(narration.charAt(i + 1));
            boolean thousands = c == ',' && i > 0 && i + 1 < narration.length()
                    && Character.isDigit(narration.charAt(i - 1)) && Character.isDigit(narration.charAt(i + 1));
            if (end && !decimal) {
                ms += SENTENCE_MS;
            } else if ((c == ',' || c == ';' || c == ':' || c == '—') && !thousands) {
                ms += COMMA_MS;
            }
        }
        return (int) Math.min(Integer.MAX_VALUE, ms);
    }

    /** Sum of the per-segment estimates; segments are read one after another. */
    public static int estimateMs(List<Segment> segments) {
        long total = 0;
        for (Segment segment : segments == null ? List.<Segment>of() : segments) {
            if (segment != null) {
                total += estimateMs(segment.narration());
            }
        }
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    static int spokenWords(String token) {
        if (token.isEmpty()) {
            return 0;
        }
        if (NUMBER.matcher(token).matches()) {
            // "1,400" reads as "one thousand four hundred": about one word per significant digit group
            String digits = token.replaceAll("[^0-9.]", "");
            String whole = digits.contains(".") ? digits.substring(0, digits.indexOf('.')) : digits;
            String fraction = digits.contains(".") ? digits.substring(digits.indexOf('.') + 1) : "";
            int words = Math.max(1, Math.min(whole.length(), 3)) + Math.max(0, (whole.length() - 1) / 3);
            words += fraction.isEmpty() ? 0 : 1 + fraction.length();
            return words + (token.endsWith("%") ? 1 : 0);
        }
        if (ACRONYM.matcher(token).matches()) {
            return Math.max(1, (token.replace("s", "").length() + 1) / 2);
        }
        return 1;
    }

    private static String trimPunctuation(String token) {
        int end = token.length();
        while (end > 0 && ".,'’-".indexOf(token.charAt(end - 1)) >= 0) {
            end--;
        }
        return token.substring(0, end);
    }
}
