package com.aistudio.production.discovery;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

import org.springframework.stereotype.Component;

import com.aistudio.content.command.CanonicalJson;
import com.aistudio.production.contract.DiscoveryProvider;
import com.aistudio.production.contract.ProductionDiscovery.Candidate;
import com.aistudio.production.contract.ProductionDiscovery.EvidencePlan;
import com.aistudio.production.contract.ProductionDiscovery.Input;
import com.aistudio.production.contract.ProductionDiscovery.Response;
import com.aistudio.production.contract.ProductionDiscovery.Scores;

/**
 * Deterministic FAKE discovery provider (R05). The seed is the SHA-256 of the request ID and the canonical input,
 * so a re-run of the same request returns the same candidates and canonical hash, while different requests draw
 * different science subjects and explanation angles. It never makes a network call and never states facts: every
 * candidate carries a question, a research plan and an empty assertion list.
 */
@Component
public class FakeDiscoveryProvider implements DiscoveryProvider {
    record Subject(String core, String question, String title, String visual, String search) {}

    record Angle(String name, String text, String suffix, String beat) {}

    static final List<Subject> SUBJECTS = List.of(
            new Subject("Rayleigh scattering", "Why is the daytime sky blue instead of violet?",
                    "Why the Sky Is Blue, Not Violet", "a prism splitting sunlight",
                    "wavelength dependence of Rayleigh scattering"),
            new Subject("Tardigrade survival", "How do tardigrades survive being completely dried out?",
                    "How Tardigrades Survive Drying Out", "a tardigrade curling into a tun",
                    "tardigrade desiccation tolerance proteins"),
            new Subject("Neutron star density", "How heavy would a teaspoon of neutron star be?",
                    "A Teaspoon of Neutron Star", "a teaspoon on a scale next to a mountain",
                    "neutron star matter density estimates"),
            new Subject("Octopus cognition", "How can an octopus solve puzzles with arms that think?",
                    "The Octopus With Thinking Arms", "neurons spread along eight arms",
                    "octopus arm neuron distribution"),
            new Subject("Lightning formation", "What makes a thundercloud build up enough charge for lightning?",
                    "Where Lightning Gets Its Charge", "ice crystals colliding inside a cloud",
                    "charge separation in thunderstorms"),
            new Subject("Mantis shrimp vision", "What does a mantis shrimp do with so many color receptors?",
                    "The Mantis Shrimp Color Puzzle", "receptor channels side by side",
                    "mantis shrimp photoreceptor types"),
            new Subject("Venus rotation", "Why is a day on Venus longer than its year?",
                    "A Day on Venus Outlasts Its Year", "two clocks for spin and orbit",
                    "Venus rotation period and orbital period"),
            new Subject("Supercooled water", "How can water stay liquid below its freezing point?",
                    "Liquid Water Below Freezing", "a bottle freezing the moment it is tapped",
                    "supercooling and ice nucleation"),
            new Subject("Bird migration magnetism", "How do migrating birds sense the magnetic field?",
                    "The Compass Inside a Bird", "field lines over a flight path",
                    "magnetoreception in migratory birds"),
            new Subject("Black hole time dilation", "Why does time slow down near a black hole?",
                    "Time Slows Near a Black Hole", "two clocks at different distances",
                    "gravitational time dilation near black holes"),
            new Subject("Plate tectonics speed", "How fast are the continents moving right now?",
                    "Continents Move as Fast as Nails Grow", "a fingernail beside a continental plate",
                    "GPS measurements of plate motion"),
            new Subject("Bioluminescence", "How do deep sea animals make their own light?",
                    "How Deep Sea Animals Make Light", "a luciferin reaction diagram",
                    "luciferin and luciferase chemistry"),
            new Subject("Sound in space", "Why can nobody hear an explosion in space?",
                    "Why Space Is Silent", "air molecules carrying a sound wave",
                    "sound propagation requires a medium"),
            new Subject("Tree communication", "How do trees share resources through fungal networks?",
                    "The Underground Network of Trees", "roots linked by fungal threads",
                    "mycorrhizal networks between trees"),
            new Subject("Saturn rings", "Why are Saturn's rings so thin compared with their width?",
                    "Saturn's Rings Are Paper Thin", "a ring edge seen side on",
                    "thickness of Saturn ring system"),
            new Subject("Sleep and memory", "What does the brain do with memories while we sleep?",
                    "What Your Brain Files While You Sleep", "a memory moving from one region to another",
                    "memory consolidation during sleep"),
            new Subject("Glass structure", "Is glass a solid or a very slow liquid?",
                    "Is Glass Secretly a Liquid?", "ordered crystal next to disordered glass",
                    "amorphous solid structure of glass"),
            new Subject("Hummingbird metabolism", "How does a hummingbird keep its tiny body fueled?",
                    "The Hummingbird Fuel Problem", "a heart rate meter racing",
                    "hummingbird metabolic rate and torpor"),
            new Subject("Auroras", "What paints the colors of the northern lights?",
                    "What Paints the Northern Lights", "solar particles hitting the upper atmosphere",
                    "oxygen and nitrogen emission in auroras"),
            new Subject("Cats landing", "How does a falling cat turn itself upright?",
                    "How a Falling Cat Flips Upright", "a cat body split into two halves",
                    "cat righting reflex mechanics"),
            new Subject("Desert cold nights", "Why do deserts get so cold at night?",
                    "Why Deserts Freeze at Night", "heat escaping through dry air",
                    "radiative cooling in arid climates"),
            new Subject("Spider silk strength", "What makes spider silk tougher than steel by weight?",
                    "Spider Silk Versus Steel", "a silk strand and a steel wire on a scale",
                    "spider dragline silk protein structure"),
            new Subject("Jupiter great red spot", "Why has the Great Red Spot lasted for centuries?",
                    "The Storm That Never Ends", "a storm larger than a planet",
                    "Great Red Spot longevity and shrinking"),
            new Subject("Electric eels", "How does an electric eel produce a strong shock?",
                    "Inside an Electric Eel's Battery", "stacked cells like batteries",
                    "electrocyte cells in electric eels"));

    static final List<Angle> ANGLES = List.of(
            new Angle("SCALE", "Compare the effect against familiar everyday scales to make it intuitive.",
                    "", "Scale comparison with %s"),
            new Angle("MECHANISM", "Walk through the mechanism one step at a time with a simple diagram.",
                    "", "Step-by-step diagram of %s"),
            new Angle("ANALOGY", "Explain it with an everyday analogy, then show where the analogy breaks.",
                    "", "Everyday analogy animation of %s"),
            new Angle("MYTH", "Contrast a common misconception with the actual mechanism.",
                    "", "Myth versus mechanism split screen of %s"),
            new Angle("WHAT_IF", "Use a what-if thought experiment to reveal the underlying physics or biology.",
                    "", "What-if thought experiment showing %s"),
            new Angle("HISTORY", "Follow how scientists figured it out, without claiming specific results.",
                    "", "Timeline of the open questions about %s"));

    private static final List<String> SOURCES = List.of("PEER_REVIEWED_PAPER", "SPACE_AGENCY",
            "UNIVERSITY_EXPLAINER", "TEXTBOOK", "MUSEUM");

    @Override
    public Response generate(Input input) {
        SplittableRandom random = new SplittableRandom(seed(input));
        List<Integer> subjects = shuffled(SUBJECTS.size(), random);
        List<Integer> angles = shuffled(ANGLES.size(), random);
        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < input.candidateCount(); i++) {
            Subject subject = SUBJECTS.get(subjects.get(i));
            Angle angle = ANGLES.get(angles.get(i % angles.size()));
            candidates.add(candidate(subject, angle, input, random));
        }
        return new Response(DiscoveryRules.RESPONSE_VERSION, List.copyOf(candidates));
    }

    private static Candidate candidate(Subject subject, Angle angle, Input input, SplittableRandom random) {
        List<String> outline = new ArrayList<>();
        outline.add("Hook: pose the question \"" + subject.question() + "\" over " + subject.visual());
        outline.add(String.format(angle.beat(), subject.core().toLowerCase(java.util.Locale.ROOT)));
        if (input.maxDurationSeconds() >= 45) {
            outline.add("Diagram comparing what viewers expect with what is still being researched");
        }
        outline.add("Close with one open question for viewers");
        String first = SOURCES.get(random.nextInt(SOURCES.size()));
        String second = SOURCES.get(random.nextInt(SOURCES.size()));
        if (second.equals(first)) {
            second = "TEXTBOOK".equals(first) ? "UNIVERSITY_EXPLAINER" : "TEXTBOOK";
        }
        List<EvidencePlan> plan = List.of(
                new EvidencePlan(first, "What do current sources say about " + subject.search() + "?"),
                new EvidencePlan(second, "Which simple diagram explains " + subject.core().toLowerCase(
                        java.util.Locale.ROOT) + " accurately?"));
        Scores suggested = new Scores(15 + random.nextInt(11), 12 + random.nextInt(9), 10 + random.nextInt(11),
                9 + random.nextInt(7), 9 + random.nextInt(7), 2 + random.nextInt(4));
        return new Candidate(subject.core(), subject.question(), subject.title(), angle.text(),
                List.copyOf(outline), plan, List.of(), suggested,
                "FAKE candidate: " + angle.name().toLowerCase(java.util.Locale.ROOT)
                        + " angle on one central question with a research plan and no unverified claims.");
    }

    static long seed(Input input) {
        String hash = CanonicalJson.sha256(java.util.Map.of("requestId", input.requestId(),
                "input", DiscoveryInputs.canonical(input)));
        return Long.parseUnsignedLong(hash.substring(0, 16), 16);
    }

    private static List<Integer> shuffled(int size, SplittableRandom random) {
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            order.add(i);
        }
        for (int i = size - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            Integer t = order.get(i);
            order.set(i, order.get(j));
            order.set(j, t);
        }
        return order;
    }
}
