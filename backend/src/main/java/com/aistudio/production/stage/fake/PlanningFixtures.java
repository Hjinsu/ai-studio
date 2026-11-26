package com.aistudio.production.stage.fake;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.aistudio.content.command.CanonicalJson;
import com.aistudio.production.contract.ProductionPlanning.ClaimKind;
import com.aistudio.production.contract.ProductionPlanning.Relation;

import tools.jackson.databind.json.JsonMapper;

/**
 * Offline FAKE fixture library (V2-03): science topics with their own documents, claims, script and scene plan.
 * Documents are written for testing and published under the reserved {@code .invalid} domain; they are not real
 * publications and are always reported as FIXTURE, never VERIFIED. A topic is chosen by keywords in the central
 * question; questions without a fixture get no documents, so their research is blocked as insufficient.
 */
public final class PlanningFixtures {
    static final List<String> FILES = List.of("neutron-star", "venus-day", "tardigrade-tun");

    public record Document(String id, String url, String title, String publisher, String text) {
        public String hash() {
            return CanonicalJson.sha256Hex(text);
        }
    }

    public record Evidence(String document, String locator, String excerpt, Relation relation) {}

    public record Claim(String id, ClaimKind kind, String text, String uncertainty, List<Evidence> evidence) {}

    public record Segment(String id, String narration, List<String> claimIds) {}

    public record Scene(List<String> segmentIds, String visualDescription, String imagePrompt, String direction,
            String transition) {}

    public record Topic(String key, List<String> match, String structure, List<Document> documents,
            List<Claim> claims, List<String> scriptClaimIds, List<String> limitations, String editorialIntent,
            List<String> hookSegmentIds, List<Segment> segments, String visualStyle, List<Scene> scenes) {

        boolean matches(String question) {
            String lower = question == null ? "" : question.toLowerCase(Locale.ROOT);
            return match.stream().anyMatch(keyword -> lower.contains(keyword.toLowerCase(Locale.ROOT)));
        }
    }

    private final List<Topic> topics;

    public PlanningFixtures(List<Topic> topics) {
        this.topics = List.copyOf(topics);
    }

    /** The bundled library from {@code classpath:planning-fixtures/}. */
    public static PlanningFixtures bundled() {
        JsonMapper json = JsonMapper.builder().build();
        List<Topic> topics = new ArrayList<>();
        for (String file : FILES) {
            String path = "planning-fixtures/" + file + ".json";
            try (InputStream in = PlanningFixtures.class.getClassLoader().getResourceAsStream(path)) {
                if (in == null) {
                    throw new IllegalStateException("Missing planning fixture " + path);
                }
                topics.add(json.readValue(new String(in.readAllBytes(), StandardCharsets.UTF_8), Topic.class));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return new PlanningFixtures(topics);
    }

    public List<Topic> topics() {
        return topics;
    }

    public Optional<Topic> forQuestion(String question) {
        return topics.stream().filter(topic -> topic.matches(question)).findFirst();
    }

    public Optional<Topic> byKey(String key) {
        return topics.stream().filter(topic -> topic.key().equals(key)).findFirst();
    }

    Optional<Document> document(String hash) {
        return topics.stream().flatMap(topic -> topic.documents().stream())
                .filter(document -> document.hash().equals(hash)).findFirst();
    }

    /** Source id the FAKE assigns to a fixture document, unique across topics. */
    static String sourceId(Topic topic, Document document) {
        return topic.key() + ":" + document.id();
    }
}
