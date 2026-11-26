package com.aistudio.production.stage;

import static com.aistudio.production.stage.Findings.blank;
import static com.aistudio.production.stage.Findings.list;

import java.net.URI;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.aistudio.production.contract.ProductionPlanning.Claim;
import com.aistudio.production.contract.ProductionPlanning.ClaimKind;
import com.aistudio.production.contract.ProductionPlanning.EvidenceRef;
import com.aistudio.production.contract.ProductionPlanning.Relation;
import com.aistudio.production.contract.ProductionPlanning.Research;
import com.aistudio.production.contract.ProductionPlanning.ResearchInput;
import com.aistudio.production.contract.ProductionPlanning.Source;
import com.aistudio.production.contract.ProductionPlanning.SourceOrigin;
import com.aistudio.production.contract.ProductionPlanning.SourceStatus;
import com.aistudio.production.contract.ProductionPlanning.Validation;
import com.aistudio.production.discovery.TextSimilarity;

/**
 * RESEARCH review (R01, R02). Source lookup status and claim support are judged separately:
 *
 * <ul>
 * <li>A source is only evidence when the server holds its document ({@link TrustedDocuments}). {@code VERIFIED}
 * is only accepted for a document retrieved through the trusted boundary; FAKE, FIXTURE and model hints can never
 * carry it. Offline FIXTURE documents count as evidence only for a FAKE provider run, and stay marked FIXTURE.</li>
 * <li>A claim is supported by a {@code SUPPORTS} judgment whose excerpt is found in that document. CONTRADICTS and
 * INSUFFICIENT never count as support.</li>
 * <li>Every claim the script may use ({@code scriptClaimIds}) must be a supported FACT, or a HYPOTHESIS or SCENARIO
 * that states its uncertainty. Unsupported claims outside that list are allowed and reported as excluded.</li>
 * </ul>
 * The number of queries and sources is not enforced: one sufficient source is enough.
 */
public final class ResearchReview {
    private final TrustedDocuments documents;

    public ResearchReview(TrustedDocuments documents) {
        this.documents = documents;
    }

    public Validation review(ResearchInput input, Research research) {
        Findings findings = new Findings();
        if (research == null) {
            findings.block("RESEARCH_STRUCTURE", "research", List.of(), "Return a research result.");
            return findings.validation();
        }
        boolean fixtureEvidence = PlanningReview.fakeRun(input);
        if (!TextSimilarity.normalized(input.question()).equals(TextSimilarity.normalized(research.question()))
                || !TextSimilarity.normalized(input.angle()).equals(TextSimilarity.normalized(research.angle()))) {
            findings.block("RESEARCH_INPUT_MISMATCH", "research.question", List.of(),
                    "Keep the selected topic's central question and explanation angle.");
        }
        Map<String, Source> sources = sources(research, findings);
        Map<String, Optional<TrustedDocuments.Document>> held = new HashMap<>();
        sources.forEach((id, source) -> held.put(id, held(source)));
        sources.forEach((id, source) -> checkSource(source, held.get(id), findings));

        Set<String> core = new HashSet<>(list(research.scriptClaimIds()));
        Set<String> claimIds = new HashSet<>();
        boolean supportedCoreFact = false;
        boolean framed = false;
        boolean excluded = false;
        for (Claim claim : list(research.claims())) {
            String location = "research.claims[" + claim.id() + "]";
            if (blank(claim.id()) || !claimIds.add(claim.id()) || claim.kind() == null || blank(claim.text())) {
                findings.block("RESEARCH_STRUCTURE", location, List.of(),
                        "Give every claim a unique id, a kind and its text.");
                continue;
            }
            Support support = support(claim, sources, held, fixtureEvidence, findings);
            boolean isCore = core.contains(claim.id());
            switch (claim.kind()) {
                case FACT -> {
                    if (isCore && support.contradicted()) {
                        findings.block("FACT_CONTRADICTED", location, support.references(),
                                "A source contradicts this fact. Correct it or remove it from the script.");
                    } else if (isCore && !support.supported()) {
                        findings.block("CORE_EVIDENCE_INSUFFICIENT", location, support.references(),
                                "Find a held source whose excerpt supports this fact, or drop it from the script.");
                    } else if (isCore) {
                        supportedCoreFact = true;
                    } else if (!support.supported() || support.contradicted()) {
                        excluded = true;
                        findings.recommend("OPTIONAL_CLAIM_EXCLUDED", location, support.references(),
                                "Not confirmed; it stays out of the script.");
                    }
                }
                case HYPOTHESIS, SCENARIO -> {
                    framed = true;
                    if (blank(claim.uncertainty())) {
                        findings.block("CLAIM_UNCERTAINTY_MISSING", location, List.of(), claim.kind()
                                == ClaimKind.SCENARIO ? "State the premise and where the scenario stops applying."
                                        : "State the reasoning and how uncertain it is.");
                    }
                }
                case UNVERIFIED -> {
                    excluded = true;
                    if (isCore) {
                        findings.block("CORE_UNVERIFIED_CLAIM", location, List.of(),
                                "An unverified claim cannot be used in the script.");
                    }
                }
            }
        }
        for (String id : core) {
            if (!claimIds.contains(id)) {
                findings.block("RESEARCH_STRUCTURE", "research.scriptClaimIds", List.of(id),
                        "Only list claims that exist in the research.");
            }
        }
        if (!supportedCoreFact && !findings.hasBlock("CORE_EVIDENCE_INSUFFICIENT")) {
            findings.block("CORE_EVIDENCE_INSUFFICIENT", "research.scriptClaimIds", List.copyOf(core),
                    "The explanation needs at least one supported fact before a script is written.");
        }
        checkInvestigations(input, research, findings);
        if ((framed || excluded) && list(research.limitations()).isEmpty()) {
            findings.recommend("LIMITATIONS_MISSING", "research.limitations", List.of(),
                    "Note what remains uncertain or was left out.");
        }
        return findings.validation();
    }

    record Support(boolean supported, boolean contradicted, List<String> references) {}

    private Support support(Claim claim, Map<String, Source> sources,
            Map<String, Optional<TrustedDocuments.Document>> held, boolean fixtureEvidence, Findings findings) {
        boolean supported = false;
        boolean contradicted = false;
        List<String> references = list(claim.evidenceRefs()).stream().map(EvidenceRef::sourceId)
                .filter(java.util.Objects::nonNull).toList();
        for (EvidenceRef ref : list(claim.evidenceRefs())) {
            String location = "research.claims[" + claim.id() + "].evidence[" + ref.sourceId() + "]";
            Source source = sources.get(ref.sourceId());
            if (source == null || ref.relation() == null) {
                findings.block("EVIDENCE_SOURCE_UNKNOWN", location, List.of(String.valueOf(ref.sourceId())),
                        "Link evidence only to a listed source with a relation.");
                continue;
            }
            Optional<TrustedDocuments.Document> document = held.getOrDefault(ref.sourceId(), Optional.empty());
            if (document.isEmpty()) {
                continue; // a lead without a held document is never evidence
            }
            if (blank(ref.supportingExcerpt()) || !contains(document.get().text(), ref.supportingExcerpt())) {
                findings.block("QUOTE_NOT_FOUND", location, List.of(ref.sourceId()),
                        "Quote only text that appears in the source document.");
                continue;
            }
            if (!usable(source, document.get(), fixtureEvidence)) {
                continue;
            }
            supported |= ref.relation() == Relation.SUPPORTS;
            contradicted |= ref.relation() == Relation.CONTRADICTS;
        }
        return new Support(supported, contradicted, references);
    }

    private static boolean usable(Source source, TrustedDocuments.Document document, boolean fixtureEvidence) {
        if (source.origin() == SourceOrigin.RETRIEVED) {
            return source.status() == SourceStatus.VERIFIED && !document.fixture();
        }
        return fixtureEvidence && source.origin() == SourceOrigin.FIXTURE && source.status() == SourceStatus.FIXTURE
                && document.fixture();
    }

    private Map<String, Source> sources(Research research, Findings findings) {
        Map<String, Source> sources = new HashMap<>();
        for (Source source : list(research.sources())) {
            if (blank(source.id()) || source.origin() == null || source.status() == null
                    || sources.putIfAbsent(source.id(), source) != null) {
                findings.block("RESEARCH_STRUCTURE", "research.sources[" + source.id() + "]", List.of(),
                        "Give every source a unique id, origin and lookup status.");
            }
        }
        return sources;
    }

    private Optional<TrustedDocuments.Document> held(Source source) {
        if (source.origin() != SourceOrigin.RETRIEVED && source.origin() != SourceOrigin.FIXTURE
                || blank(source.documentHash())) {
            return Optional.empty();
        }
        return documents.find(source.documentHash()).filter(document -> document.fixture()
                == (source.origin() == SourceOrigin.FIXTURE));
    }

    private static void checkSource(Source source, Optional<TrustedDocuments.Document> held, Findings findings) {
        String location = "research.sources[" + source.id() + "]";
        List<String> ref = List.of(source.id());
        if (source.url() != null && !validUrl(source.url(), source.origin() == SourceOrigin.FIXTURE)) {
            findings.block("SOURCE_URL_INVALID", location, ref, "Use a public http(s) address.");
        }
        if (source.status() == SourceStatus.VERIFIED && (source.origin() != SourceOrigin.RETRIEVED
                || held.isEmpty() || blank(source.checkedAtUtc()))) {
            findings.block("SOURCE_FALSE_VERIFICATION", location, ref,
                    "Only a document retrieved and identified by the server may be marked VERIFIED.");
        }
        if (source.status() == SourceStatus.FIXTURE && source.origin() != SourceOrigin.FIXTURE) {
            findings.block("SOURCE_FALSE_VERIFICATION", location, ref, "Only offline fixtures are marked FIXTURE.");
        }
        if (source.origin() == SourceOrigin.FIXTURE || source.origin() == SourceOrigin.RETRIEVED) {
            boolean matches = held.isPresent() && held.get().url().equals(source.url())
                    && held.get().title().equals(source.title());
            if (!matches) {
                findings.block("SOURCE_NOT_FOUND", location, ref,
                        "The cited document is not held by the server. Remove it or retrieve it.");
            }
        }
    }

    static boolean validUrl(String url, boolean fixture) {
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            if (!(scheme.equals("https") || scheme.equals("http")) || host.isEmpty() || uri.getUserInfo() != null) {
                return false;
            }
            if (host.endsWith(".invalid")) {
                return fixture; // reserved name: offline fixtures only
            }
            if (host.equals("localhost") || host.endsWith(".localhost") || host.endsWith(".local")
                    || host.endsWith(".internal")) {
                return false;
            }
            if (host.startsWith("[")) {
                return false; // IP literals are not accepted as public document addresses
            }
            if (host.matches("[0-9.]+")) {
                return publicIpv4(host);
            }
            return host.contains(".");
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean publicIpv4(String host) {
        String[] parts = host.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        int[] octets = new int[4];
        for (int i = 0; i < 4; i++) {
            if (parts[i].isEmpty() || parts[i].length() > 3) {
                return false;
            }
            octets[i] = Integer.parseInt(parts[i]);
            if (octets[i] > 255) {
                return false;
            }
        }
        int a = octets[0];
        int b = octets[1];
        return !(a == 0 || a == 10 || a == 127 || a >= 224 || (a == 169 && b == 254) || (a == 172 && b >= 16 && b <= 31)
                || (a == 192 && b == 168) || (a == 100 && b >= 64 && b <= 127));
    }

    private static void checkInvestigations(ResearchInput input, Research research, Findings findings) {
        Set<String> asked = new HashSet<>();
        for (var investigation : list(research.investigations())) {
            if (blank(investigation.id()) || blank(investigation.question())) {
                findings.block("RESEARCH_STRUCTURE", "research.investigations", List.of(),
                        "Give every investigation an id and a question.");
                continue;
            }
            asked.add(TextSimilarity.normalized(investigation.question()));
        }
        for (var plan : list(input.investigationPlan())) {
            if (!asked.contains(TextSimilarity.normalized(plan.searchQuestion()))) {
                findings.recommend("INVESTIGATION_PLAN_NOT_COVERED", "research.investigations",
                        List.of(String.valueOf(plan.searchQuestion())), "Record the outcome of each planned investigation.");
            }
        }
    }

    static boolean contains(String text, String excerpt) {
        return squash(text).contains(squash(excerpt));
    }

    private static String squash(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }
}
