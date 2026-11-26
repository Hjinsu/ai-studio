package com.aistudio.production.discovery;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.aistudio.content.command.CanonicalJson;
import com.aistudio.production.contract.ProductionDiscovery.Candidate;
import com.aistudio.production.contract.ProductionDiscovery.Claim;
import com.aistudio.production.contract.ProductionDiscovery.EvidencePlan;
import com.aistudio.production.contract.ProductionDiscovery.Response;
import com.aistudio.production.contract.ProductionDiscovery.Scores;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Canonical JSON of a discovery response and a strict parser for provider text. The canonical form (sorted keys,
 * no whitespace, integers only) is what is stored and hashed; the same response always has the same SHA-256.
 * The parser accepts exactly the contract fields: unknown or duplicate keys, wrong types, fractions and nesting
 * deeper than the contract are rejected.
 */
public final class DiscoveryResponseCodec {
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

    private static final Set<String> RESPONSE_FIELDS = Set.of("version", "candidates");
    private static final Set<String> CANDIDATE_FIELDS = Set.of("coreTopic", "centralQuestion", "title", "angle",
            "outline", "evidencePlan", "assertions", "suggestedScores", "rationale");
    private static final Set<String> PLAN_FIELDS = Set.of("sourceType", "searchQuestion");
    private static final Set<String> CLAIM_FIELDS = Set.of("assertion", "evidenceId");
    private static final Set<String> SCORE_FIELDS = Set.of("interest", "channelFit", "originality",
            "visualPotential", "evidencePotential", "costEfficiency");

    private DiscoveryResponseCodec() {}

    public static String canonicalJson(Response response) {
        return CanonicalJson.write(tree(response));
    }

    public static String sha256(Response response) {
        return CanonicalJson.sha256Hex(canonicalJson(response));
    }

    /** Parses provider text. The result still has to pass {@link DiscoveryResponseValidator#validate}. */
    public static Response parse(String text) {
        if (text == null || text.getBytes(StandardCharsets.UTF_8).length > DiscoveryRules.MAX_RESPONSE_BYTES) {
            throw new DiscoveryResponseException("response", "missing or too large");
        }
        Object root;
        try {
            root = JSON.readValue(text, Object.class);
        } catch (RuntimeException e) {
            throw new DiscoveryResponseException("response", "not valid JSON");
        }
        depth("response", root, 0);
        Map<?, ?> object = object("response", root, RESPONSE_FIELDS);
        List<?> raw = array("candidates", object.get("candidates"));
        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            candidates.add(candidate("candidates[" + i + "]", raw.get(i)));
        }
        return new Response(integer("version", object.get("version")), candidates);
    }

    private static Candidate candidate(String path, Object value) {
        Map<?, ?> c = object(path, value, CANDIDATE_FIELDS);
        List<String> outline = new ArrayList<>();
        List<?> beats = array(path + ".outline", c.get("outline"));
        for (int i = 0; i < beats.size(); i++) {
            outline.add(string(path + ".outline[" + i + "]", beats.get(i)));
        }
        List<EvidencePlan> plans = new ArrayList<>();
        List<?> rawPlans = array(path + ".evidencePlan", c.get("evidencePlan"));
        for (int i = 0; i < rawPlans.size(); i++) {
            String p = path + ".evidencePlan[" + i + "]";
            Map<?, ?> plan = object(p, rawPlans.get(i), PLAN_FIELDS);
            plans.add(new EvidencePlan(string(p + ".sourceType", plan.get("sourceType")),
                    string(p + ".searchQuestion", plan.get("searchQuestion"))));
        }
        List<Claim> claims = new ArrayList<>();
        List<?> rawClaims = array(path + ".assertions", c.get("assertions"));
        for (int i = 0; i < rawClaims.size(); i++) {
            String p = path + ".assertions[" + i + "]";
            Map<?, ?> claim = objectAllowingMissing(p, rawClaims.get(i), CLAIM_FIELDS, Set.of("evidenceId"));
            Object evidence = claim.get("evidenceId");
            claims.add(new Claim(string(p + ".assertion", claim.get("assertion")),
                    evidence == null ? null : string(p + ".evidenceId", evidence)));
        }
        Map<?, ?> s = object(path + ".suggestedScores", c.get("suggestedScores"), SCORE_FIELDS);
        String sp = path + ".suggestedScores.";
        Scores scores = new Scores(integer(sp + "interest", s.get("interest")),
                integer(sp + "channelFit", s.get("channelFit")), integer(sp + "originality", s.get("originality")),
                integer(sp + "visualPotential", s.get("visualPotential")),
                integer(sp + "evidencePotential", s.get("evidencePotential")),
                integer(sp + "costEfficiency", s.get("costEfficiency")));
        return new Candidate(string(path + ".coreTopic", c.get("coreTopic")),
                string(path + ".centralQuestion", c.get("centralQuestion")), string(path + ".title", c.get("title")),
                string(path + ".angle", c.get("angle")), outline, plans, claims, scores,
                string(path + ".rationale", c.get("rationale")));
    }

    private static Map<String, Object> tree(Response response) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", response.version());
        List<Object> candidates = new ArrayList<>();
        for (Candidate c : response.candidates()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("coreTopic", c.coreTopic());
            m.put("centralQuestion", c.centralQuestion());
            m.put("title", c.title());
            m.put("angle", c.angle());
            m.put("outline", List.copyOf(c.outline()));
            List<Object> plans = new ArrayList<>();
            for (EvidencePlan plan : c.evidencePlan()) {
                plans.add(Map.of("sourceType", plan.sourceType(), "searchQuestion", plan.searchQuestion()));
            }
            m.put("evidencePlan", plans);
            List<Object> claims = new ArrayList<>();
            for (Claim claim : c.assertions()) {
                Map<String, Object> cm = new LinkedHashMap<>();
                cm.put("assertion", claim.assertion());
                cm.put("evidenceId", claim.evidenceId());
                claims.add(cm);
            }
            m.put("assertions", claims);
            Scores s = c.suggestedScores();
            m.put("suggestedScores", Map.of("interest", s.interest(), "channelFit", s.channelFit(),
                    "originality", s.originality(), "visualPotential", s.visualPotential(),
                    "evidencePotential", s.evidencePotential(), "costEfficiency", s.costEfficiency()));
            m.put("rationale", c.rationale());
            candidates.add(m);
        }
        root.put("candidates", candidates);
        return root;
    }

    private static void depth(String path, Object value, int level) {
        if (level > DiscoveryRules.MAX_JSON_DEPTH) {
            throw new DiscoveryResponseException(path, "nested too deeply");
        }
        if (value instanceof Map<?, ?> map) {
            for (Object child : map.values()) {
                depth(path, child, level + 1);
            }
        } else if (value instanceof List<?> list) {
            for (Object child : list) {
                depth(path, child, level + 1);
            }
        }
    }

    private static Map<?, ?> object(String path, Object value, Set<String> fields) {
        return objectAllowingMissing(path, value, fields, Set.of());
    }

    private static Map<?, ?> objectAllowingMissing(String path, Object value, Set<String> fields,
            Set<String> optional) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new DiscoveryResponseException(path, "must be an object");
        }
        for (Object key : map.keySet()) {
            if (!fields.contains(key)) {
                throw new DiscoveryResponseException(path, "unknown field");
            }
        }
        for (String field : fields) {
            if (!optional.contains(field) && !map.containsKey(field)) {
                throw new DiscoveryResponseException(path + "." + field, "missing");
            }
        }
        return map;
    }

    private static List<?> array(String path, Object value) {
        if (!(value instanceof List<?> list)) {
            throw new DiscoveryResponseException(path, "must be an array");
        }
        return list;
    }

    private static String string(String path, Object value) {
        if (!(value instanceof String text)) {
            throw new DiscoveryResponseException(path, "must be a string");
        }
        return text;
    }

    private static int integer(String path, Object value) {
        if (value instanceof Integer i) {
            return i;
        }
        if (value instanceof Long || value instanceof BigInteger) {
            throw new DiscoveryResponseException(path, "out of range");
        }
        throw new DiscoveryResponseException(path, "must be an integer");
    }
}
