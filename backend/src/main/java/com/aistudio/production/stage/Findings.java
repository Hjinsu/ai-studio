package com.aistudio.production.stage;

import java.util.ArrayList;
import java.util.List;

import com.aistudio.production.contract.ProductionPlanning.Finding;
import com.aistudio.production.contract.ProductionPlanning.Severity;
import com.aistudio.production.contract.ProductionPlanning.Validation;

/** Collects review findings in a stable order. Duplicate findings (same severity, code and location) collapse. */
final class Findings {
    private final List<Finding> items = new ArrayList<>();

    void block(String code, String location, List<String> references, String correction) {
        add(new Finding(Severity.BLOCK, code, location, List.copyOf(references), correction));
    }

    void recommend(String code, String location, List<String> references, String correction) {
        add(new Finding(Severity.RECOMMENDATION, code, location, List.copyOf(references), correction));
    }

    private void add(Finding finding) {
        boolean duplicate = items.stream().anyMatch(item -> item.severity() == finding.severity()
                && item.code().equals(finding.code()) && item.location().equals(finding.location()));
        if (!duplicate) {
            items.add(finding);
        }
    }

    boolean hasBlock(String code) {
        return items.stream().anyMatch(item -> item.severity() == Severity.BLOCK && item.code().equals(code));
    }

    Validation validation() {
        return new Validation(items);
    }

    static <T> List<T> list(List<T> values) {
        return values == null ? List.of() : values.stream().filter(java.util.Objects::nonNull).toList();
    }

    static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
