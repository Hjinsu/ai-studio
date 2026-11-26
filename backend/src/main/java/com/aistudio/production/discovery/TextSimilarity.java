package com.aistudio.production.discovery;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.aistudio.topic.TopicNormalizer;

/**
 * Rule v1 lexical comparison for English discovery text (R02). Text is normalized like Topic titles, split into
 * words, stripped of stop words, reduced by a light suffix rule and mapped through a small synonym table to
 * concepts. Similarity is the Jaccard index of concept sets.
 *
 * <p>Known limit: synonyms outside the table, metaphors and different phrasing of the same question can be missed,
 * and distinct questions that share many words can look similar. Borderline results are blocked rather than
 * passed. A meaning-based evaluation is a separate, approved step when a real provider is connected.
 */
public final class TextSimilarity {
    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");

    private static final Set<String> STOP_WORDS = Set.of("a", "an", "the", "and", "or", "but", "of", "to", "in",
            "on", "at", "for", "from", "by", "with", "into", "onto", "about", "as", "is", "are", "was", "were", "be",
            "been", "being", "do", "does", "did", "doing", "can", "could", "would", "should", "will", "may", "might",
            "it", "its", "this", "that", "these", "those", "there", "their", "they", "them", "we", "our", "you",
            "your", "i", "my", "me", "he", "she", "his", "her", "what", "why", "how", "when", "where", "which", "who",
            "whom", "whose", "really", "actually", "just", "so", "very", "than", "then", "if", "not", "no", "yes",
            "all", "any", "some", "more", "most", "less", "much", "many", "such", "own", "same", "other", "also",
            "get", "gets", "got", "make", "makes", "made", "come", "comes", "came", "happen", "happens", "explained",
            "explain", "explains", "secret", "truth", "reason", "reasons", "behind", "ever", "even", "only",
            "instead", "rather", "look", "looks", "seem", "seems", "appear", "appears");

    /** Concept table: word to canonical concept. Applied after the suffix rule. */
    private static final Map<String, String> SYNONYMS = Map.ofEntries(
            Map.entry("azure", "blue"), Map.entry("sky", "atmosphere"), Map.entry("air", "atmosphere"),
            Map.entry("heaven", "atmosphere"), Map.entry("warm", "heat"), Map.entry("hot", "heat"),
            Map.entry("thermal", "heat"), Map.entry("temperature", "heat"), Map.entry("cold", "cool"),
            Map.entry("chilly", "cool"), Map.entry("huge", "large"), Map.entry("big", "large"),
            Map.entry("giant", "large"), Map.entry("enormous", "large"), Map.entry("tiny", "small"),
            Map.entry("little", "small"), Map.entry("miniature", "small"), Map.entry("fast", "speed"),
            Map.entry("quick", "speed"), Map.entry("rapid", "speed"), Map.entry("velocity", "speed"),
            Map.entry("planet", "planet"), Map.entry("world", "planet"), Map.entry("earth", "earth"),
            Map.entry("globe", "earth"), Map.entry("sun", "sun"), Map.entry("solar", "sun"),
            Map.entry("star", "star"), Map.entry("stellar", "star"), Map.entry("moon", "moon"),
            Map.entry("lunar", "moon"), Map.entry("ocean", "sea"), Map.entry("sea", "sea"),
            Map.entry("water", "water"), Map.entry("h2o", "water"), Map.entry("light", "light"),
            Map.entry("sunlight", "light"), Map.entry("photon", "light"), Map.entry("scatter", "scatter"),
            Map.entry("scattering", "scatter"), Map.entry("scattered", "scatter"), Map.entry("bend", "bend"),
            Map.entry("refract", "bend"), Map.entry("refraction", "bend"), Map.entry("colour", "color"),
            Map.entry("hue", "color"),
            Map.entry("glow", "shine"), Map.entry("shine", "shine"),
            Map.entry("bright", "shine"), Map.entry("brain", "brain"), Map.entry("mind", "brain"),
            Map.entry("cerebral", "brain"), Map.entry("die", "death"), Map.entry("dying", "death"),
            Map.entry("dead", "death"), Map.entry("explode", "explosion"), Map.entry("blast", "explosion"),
            Map.entry("start", "begin"), Map.entry("begin", "begin"), Map.entry("origin", "begin"),
            Map.entry("first", "begin"), Map.entry("old", "age"), Map.entry("ancient", "age"),
            Map.entry("aging", "age"), Map.entry("ageing", "age"), Map.entry("black", "dark"),
            Map.entry("dark", "dark"), Map.entry("hole", "hole"), Map.entry("gravity", "gravity"),
            Map.entry("gravitational", "gravity"), Map.entry("pull", "force"), Map.entry("force", "force"),
            Map.entry("magnet", "magnet"), Map.entry("magnetic", "magnet"), Map.entry("electric", "electric"),
            Map.entry("electricity", "electric"), Map.entry("lightning", "lightning"),
            Map.entry("thunder", "lightning"), Map.entry("cell", "cell"), Map.entry("dna", "gene"),
            Map.entry("gene", "gene"), Map.entry("genetic", "gene"), Map.entry("sleep", "sleep"),
            Map.entry("dream", "sleep"), Map.entry("volcano", "volcano"), Map.entry("volcanic", "volcano"),
            Map.entry("lava", "volcano"), Map.entry("quake", "earthquake"), Map.entry("earthquake", "earthquake"),
            Map.entry("tremor", "earthquake"), Map.entry("ice", "ice"), Map.entry("frozen", "ice"),
            Map.entry("freeze", "ice"), Map.entry("cloud", "cloud"), Map.entry("rain", "rain"),
            Map.entry("storm", "storm"), Map.entry("wind", "wind"), Map.entry("sound", "sound"),
            Map.entry("noise", "sound"), Map.entry("acoustic", "sound"), Map.entry("time", "time"),
            Map.entry("clock", "time"), Map.entry("space", "space"), Map.entry("cosmos", "space"),
            Map.entry("universe", "space"), Map.entry("cosmic", "space"), Map.entry("galaxy", "galaxy"),
            Map.entry("galactic", "galaxy"));

    private TextSimilarity() {}

    /** Ordered distinct concepts of a text. */
    public static List<String> concepts(String text) {
        Set<String> result = new LinkedHashSet<>();
        for (String word : words(text)) {
            if (STOP_WORDS.contains(word)) {
                continue;
            }
            String concept = concept(word);
            if (!concept.isEmpty() && !STOP_WORDS.contains(concept)) {
                result.add(concept);
            }
        }
        return List.copyOf(result);
    }

    /** Distinct adjacent concept pairs ("2-word phrases") of a text, in order. */
    public static List<String> bigrams(String text) {
        List<String> list = concepts(text);
        Set<String> pairs = new LinkedHashSet<>();
        for (int i = 1; i < list.size(); i++) {
            pairs.add(list.get(i - 1) + " " + list.get(i));
        }
        return List.copyOf(pairs);
    }

    public static double jaccard(List<String> a, List<String> b) {
        if (a.isEmpty() && b.isEmpty()) {
            return 0.0;
        }
        Set<String> union = new LinkedHashSet<>(a);
        union.addAll(b);
        Set<String> intersection = new LinkedHashSet<>(a);
        intersection.retainAll(Set.copyOf(b));
        return (double) intersection.size() / union.size();
    }

    /** True when every concept of {@code part} already appears in {@code whole}. */
    public static boolean coveredBy(List<String> part, List<String> whole) {
        return !part.isEmpty() && Set.copyOf(whole).containsAll(part);
    }

    /** Topic-normalized text, used for exact title and question comparison. */
    public static String normalized(String text) {
        return TopicNormalizer.normalize(text == null ? "" : text);
    }

    /** Whether the normalized text contains the phrase as whole words. */
    public static boolean containsPhrase(String text, String phrase) {
        List<String> haystack = words(text);
        List<String> needle = words(phrase);
        if (needle.isEmpty()) {
            return false;
        }
        List<String> stemmedHaystack = haystack.stream().map(TextSimilarity::stem).toList();
        List<String> stemmedNeedle = needle.stream().map(TextSimilarity::stem).toList();
        for (int i = 0; i + stemmedNeedle.size() <= stemmedHaystack.size(); i++) {
            if (stemmedHaystack.subList(i, i + stemmedNeedle.size()).equals(stemmedNeedle)) {
                return true;
            }
        }
        return false;
    }

    static List<String> words(String text) {
        List<String> result = new ArrayList<>();
        for (String part : NON_WORD.split(normalized(text))) {
            if (!part.isEmpty()) {
                result.add(part);
            }
        }
        return result;
    }

    private static String concept(String word) {
        String direct = SYNONYMS.get(word);
        if (direct != null) {
            return direct;
        }
        String stemmed = stem(word);
        return SYNONYMS.getOrDefault(stemmed, stemmed);
    }

    /** Light English suffix rule: plural and simple verb endings. Deliberately conservative. */
    static String stem(String word) {
        if (word.length() > 4 && word.endsWith("ies")) {
            return word.substring(0, word.length() - 3) + "y";
        }
        if (word.length() > 5 && word.endsWith("ing")) {
            return word.substring(0, word.length() - 3);
        }
        if (word.length() > 4 && word.endsWith("ed")) {
            return word.substring(0, word.length() - 2);
        }
        if (word.length() > 3 && word.endsWith("s") && !word.endsWith("ss") && !word.endsWith("us")
                && !word.endsWith("is")) {
            return word.substring(0, word.length() - 1);
        }
        return word;
    }
}
