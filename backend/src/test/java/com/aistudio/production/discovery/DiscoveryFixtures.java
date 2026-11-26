package com.aistudio.production.discovery;

import java.util.ArrayList;
import java.util.List;

import com.aistudio.production.contract.DiscoveryEvaluator.CatalogEntry;
import com.aistudio.production.contract.ProductionDiscovery.Candidate;
import com.aistudio.production.contract.ProductionDiscovery.EvidencePlan;
import com.aistudio.production.contract.ProductionDiscovery.Input;
import com.aistudio.production.contract.ProductionDiscovery.Response;
import com.aistudio.production.contract.ProductionDiscovery.Scores;
import com.aistudio.topic.TopicNormalizer;

/** IFNOVA-like English science Shorts inputs and hand-written candidates for rule fixtures. */
public final class DiscoveryFixtures {
    public static final String STRATEGY = "English science Shorts that explain one surprising question with visuals";
    public static final String AUDIENCE = "Curious teens and adults who like space, biology and physics";

    private DiscoveryFixtures() {}

    public static Input input(long requestId, int count) {
        return input(requestId, count, List.of());
    }

    public static Input input(long requestId, int count, List<String> prohibited) {
        return new Input(requestId, 7, "en", count, 40, 60, STRATEGY, AUDIENCE, prohibited,
                DiscoveryRules.RULES_VERSION);
    }

    public static Scores goodScores() {
        return new Scores(20, 16, 16, 12, 12, 4);
    }

    public static Candidate candidate(String core, String question, String title, String angle) {
        return new Candidate(core, question, title, angle,
                List.of("Hook over a diagram of " + core.toLowerCase(java.util.Locale.ROOT),
                        "Step-by-step animation of the mechanism",
                        "Close with one open question for viewers"),
                List.of(new EvidencePlan("PEER_REVIEWED_PAPER", "What do current sources say about " + core + "?"),
                        new EvidencePlan("UNIVERSITY_EXPLAINER", "Which simple diagram explains " + core + "?")),
                List.of(), goodScores(), "One central question with a research plan.");
    }

    /** Distinct filler candidates for padding a batch to the requested count. */
    public static List<Candidate> fillers(int count) {
        List<Candidate> list = new ArrayList<>();
        String[][] pool = {
                { "Tardigrade survival", "How do tardigrades survive being dried out?", "How Tardigrades Survive Drying Out" },
                { "Neutron star density", "How heavy is a teaspoon of neutron star?", "A Teaspoon of Neutron Star" },
                { "Octopus cognition", "How can an octopus solve puzzles with its arms?", "The Octopus With Thinking Arms" },
                { "Venus rotation", "Why is a day on Venus longer than its year?", "A Day on Venus Outlasts Its Year" },
                { "Supercooled water", "How can water stay liquid below freezing?", "Liquid Water Below Freezing" },
                { "Bird magnetoreception", "How do migrating birds sense the magnetic field?", "The Compass Inside a Bird" },
                { "Saturn rings", "Why are Saturn's rings so thin?", "Saturn's Rings Are Paper Thin" },
                { "Cat righting reflex", "How does a falling cat turn upright?", "How a Falling Cat Flips Upright" },
                { "Spider silk", "What makes spider silk so tough by weight?", "Spider Silk Versus Steel" },
                { "Electric eels", "How does an electric eel produce a shock?", "Inside an Electric Eel's Battery" } };
        String[] angles = { "Compare it against familiar everyday scales.", "Walk through the mechanism step by step.",
                "Explain with an everyday analogy and where it breaks.", "Contrast a misconception with the mechanism.",
                "Use a what-if thought experiment." };
        for (int i = 0; i < count; i++) {
            list.add(candidate(pool[i][0], pool[i][1], pool[i][2], angles[i % angles.length]));
        }
        return list;
    }

    public static Response response(int count, Candidate... first) {
        List<Candidate> list = new ArrayList<>(List.of(first));
        for (Candidate filler : fillers(count)) {
            if (list.size() == count) {
                break;
            }
            list.add(filler);
        }
        return new Response(DiscoveryRules.RESPONSE_VERSION, list);
    }

    public static CatalogEntry entry(String id, String title, String brief) {
        return new CatalogEntry("t" + id, "c" + id, "r" + id,
                TopicNormalizer.hash(TopicNormalizer.VERSION, TopicNormalizer.normalize(title)), title, brief);
    }
}
