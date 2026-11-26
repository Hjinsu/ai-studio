package com.aistudio.production.stage;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rule v1 reading of English narration sentences for the local review (V2-03). It finds sentences that state
 * something as fact (numbers, comparisons, causes, superlatives and absolutes), sentences that are framed as a
 * question, thought experiment or hedge, and the numbers a sentence uses.
 *
 * <p>Known limit: this is a pattern list, not a reading of meaning. It catches the phrasing used in the offline
 * fixtures and common Shorts narration, but a factual statement without any of these markers is not detected.
 * Meaning-based review is a separate, approved step when a real provider is connected (V2-05).
 */
final class Statements {
    /** Sentence ends are . ! ? followed by whitespace, so decimals such as 1.4 stay in one sentence. */
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?])\\s+");
    private static final Pattern DIGITS = Pattern.compile("\\d[\\d,]*(?:\\.\\d+)?");
    private static final Map<String, String> NUMBER_WORDS = Map.ofEntries(
            Map.entry("two", "2"), Map.entry("three", "3"), Map.entry("four", "4"), Map.entry("five", "5"),
            Map.entry("six", "6"), Map.entry("seven", "7"), Map.entry("eight", "8"), Map.entry("nine", "9"),
            Map.entry("ten", "10"), Map.entry("eleven", "11"), Map.entry("twelve", "12"),
            Map.entry("twenty", "20"), Map.entry("thirty", "30"), Map.entry("fifty", "50"),
            Map.entry("hundred", "100"), Map.entry("hundreds", "100"), Map.entry("thousand", "1000"),
            Map.entry("thousands", "1000"), Map.entry("million", "1000000"), Map.entry("millions", "1000000"),
            Map.entry("billion", "1000000000"), Map.entry("billions", "1000000000"),
            Map.entry("trillion", "1000000000000"), Map.entry("half", "0.5"), Map.entry("twice", "2"),
            Map.entry("double", "2"), Map.entry("triple", "3"));
    private static final Pattern COMPARISON = Pattern.compile(
            "\\b(\\w+er|more|less|fewer)\\s+(\\w+\\s+){0,3}than\\b|\\btimes\\s+(more|less|as|bigger|heavier|faster|"
                    + "longer|smaller|denser)\\b|\\bas\\s+\\w+\\s+as\\b");
    private static final Pattern CAUSAL = Pattern.compile(
            "\\b(because|causes?|caused|causing|leads?\\s+to|led\\s+to|due\\s+to|results?\\s+in|so\\s+that|"
                    + "which\\s+means|that\\s+means|this\\s+means|thanks\\s+to|therefore|so\\s+the)\\b");
    private static final Pattern ABSOLUTE = Pattern.compile(
            "\\b(always|never|every|proven|proves|proved|definitely|certainly|the\\s+only|largest|smallest|"
                    + "fastest|slowest|heaviest|densest|hottest|coldest|oldest|longest|shortest|strongest|"
                    + "scientists\\s+(found|discovered|proved|confirmed))\\b");
    private static final Pattern HEDGE = Pattern.compile(
            "^(imagine|picture|suppose|pretend|what\\s+if)\\b|\\b(might|could|may|would|perhaps|possibly|maybe|"
                    + "likely|unlikely|probably|if|suggests?|think|thinks|unknown|unclear|nobody\\s+knows|"
                    + "not\\s+sure|still\\s+debated|one\\s+idea|one\\s+theory|hypothesis)\\b");

    private Statements() {}

    record Sentence(String text, boolean question, boolean hedged, boolean numeric, boolean comparison,
            boolean causal, boolean absolute) {

        /** States something as fact: a number, comparison, cause, superlative or absolute without framing. */
        boolean assertsFact() {
            return !question && !hedged && (numeric || comparison || causal || absolute);
        }
    }

    static List<Sentence> sentences(String text) {
        List<Sentence> result = new ArrayList<>();
        if (text == null) {
            return result;
        }
        for (String part : SENTENCE_END.split(text.trim())) {
            String raw = part.trim();
            if (raw.isEmpty() || raw.chars().noneMatch(Character::isLetterOrDigit)) {
                continue;
            }
            String lower = raw.toLowerCase(Locale.ROOT);
            result.add(new Sentence(raw, raw.endsWith("?"), HEDGE.matcher(lower).find(), !numbers(raw).isEmpty(),
                    COMPARISON.matcher(lower).find(), CAUSAL.matcher(lower).find(),
                    ABSOLUTE.matcher(lower).find()));
        }
        return result;
    }

    static boolean causal(String text) {
        return text != null && CAUSAL.matcher(text.toLowerCase(Locale.ROOT)).find();
    }

    /** Normalized numbers used in a text: digits without separators, and number words as digits. */
    static Set<String> numbers(String text) {
        Set<String> result = new LinkedHashSet<>();
        if (text == null) {
            return result;
        }
        Matcher digits = DIGITS.matcher(text);
        while (digits.find()) {
            String value = digits.group().replace(",", "");
            if (value.contains(".")) {
                value = value.replaceAll("0+$", "").replaceAll("\\.$", "");
            }
            result.add(value);
        }
        for (String word : text.toLowerCase(Locale.ROOT).split("[^\\p{L}]+")) {
            String number = NUMBER_WORDS.get(word);
            if (number != null) {
                result.add(number);
            }
        }
        return result;
    }
}
