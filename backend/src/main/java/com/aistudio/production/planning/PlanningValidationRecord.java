package com.aistudio.production.planning;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import com.aistudio.production.contract.PlanningJson;
import com.aistudio.production.contract.ProductionPlanning;
import com.aistudio.production.contract.ProductionPlanning.Finding;
import com.aistudio.production.contract.ProductionPlanning.Severity;
import com.aistudio.production.contract.ProductionPlanning.Validation;

final class PlanningValidationRecord {
    private static final int MAX_FINDINGS = 64;
    private static final int MAX_REFERENCES = 4;

    private PlanningValidationRecord() {}

    static String encode(Validation validation) {
        var findings = validation.findings();
        long blocks = findings.stream().filter(finding -> finding.severity() == Severity.BLOCK).count();
        var retained = findings.stream()
                .sorted(Comparator.comparing(finding -> finding.severity() != Severity.BLOCK))
                .limit(MAX_FINDINGS)
                .map(PlanningValidationRecord::shorten)
                .toList();
        int count = retained.size();
        while (true) {
            var stored = new ArrayList<>(retained.subList(0, count));
            int omitted = findings.size() - count;
            if (omitted > 0) {
                long omittedBlocks = Math.max(0, blocks - count);
                Severity severity = blocks > 0 && count == 0 ? Severity.BLOCK : Severity.RECOMMENDATION;
                stored.add(new Finding(severity, "VALIDATION_FINDINGS_OMITTED", "validation", List.of(),
                        String.format(Locale.ROOT,
                                "%d findings omitted (%d required blocks, %d recommendations) to fit the stored review.",
                                omitted, omittedBlocks, omitted - omittedBlocks)));
            }
            String encoded = PlanningJson.encode(new Validation(stored)).json();
            if (encoded.getBytes(StandardCharsets.UTF_8).length <= ProductionPlanning.MAX_BODY_BYTES) {
                return encoded;
            }
            count /= 2;
        }
    }

    private static Finding shorten(Finding finding) {
        return new Finding(finding.severity(), shorten(finding.code(), 64), shorten(finding.location(), 160),
                finding.references().stream().limit(MAX_REFERENCES).map(ref -> shorten(ref, 96)).toList(),
                shorten(finding.correction(), 320));
    }

    private static String shorten(String value, int limit) {
        if (value == null || value.codePointCount(0, value.length()) <= limit) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, limit - 1)) + "…";
    }
}
