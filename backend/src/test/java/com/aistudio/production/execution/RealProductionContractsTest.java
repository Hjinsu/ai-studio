package com.aistudio.production.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.aistudio.content.command.CanonicalJson;
import com.aistudio.production.contract.ProductionMediaBudget;
import com.aistudio.production.contract.ProductionStage;
import com.aistudio.production.contract.RealMediaProfile;
import com.aistudio.production.contract.WorkerExchange;
import com.aistudio.production.contract.WorkerExchange.Identity;

class RealProductionContractsTest {
    @TempDir Path directory;

    @Test
    void requestOrderChangesOnlyForNewProfile() {
        assertThat(RealMediaProfile.order("v2-media-1"))
                .containsSubsequence(ProductionStage.IMAGE, ProductionStage.TTS);
        assertThat(RealMediaProfile.order(RealMediaProfile.PIPELINE))
                .containsSubsequence(ProductionStage.SELECT, ProductionStage.SCENES,
                        ProductionStage.TTS, ProductionStage.IMAGE);
    }

    @Test
    void lowerRequestLimitAndUnrecognizedExposureAreIncluded() {
        assertThat(ProductionMediaBudget.requestLimit(new BigDecimal("5000"))).isEqualByComparingTo("3000");
        assertThat(ProductionMediaBudget.permits(new BigDecimal("2900"), new BigDecimal("1500"),
                new BigDecimal("1200"), new BigDecimal("200"))).isTrue();
        assertThat(ProductionMediaBudget.permits(new BigDecimal("5000"), new BigDecimal("1500"),
                new BigDecimal("1200"), new BigDecimal("300.000001"))).isFalse();
    }

    @Test
    void resultMustBindToExistingDraftAndActiveEpoch() {
        var active = identity(1);
        var old = identity(2);
        String resultHash = CanonicalJson.sha256Hex("{}");
        var draft = new WorkerExchange.Draft(active, "{}", resultHash);
        WorkerExchange.verifyLineage(active, draft, new WorkerExchange.Review(active, resultHash, "{}", resultHash));
        assertThatThrownBy(() -> WorkerExchange.verifyLineage(active, draft,
                new WorkerExchange.Review(old, resultHash, "{}", resultHash)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorkerExchange.Review(active, resultHash, "changed", resultHash))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void publicationReplaysIdenticalBytesAndRejectsConflictingResult() throws IOException {
        var files = new WorkerExchangeFiles(directory);
        var identity = identity(1);
        files.publish(WorkerExchangeFiles.Kind.RESULT, identity, "{}");
        files.publish(WorkerExchangeFiles.Kind.RESULT, identity, "{}");
        assertThatThrownBy(() -> files.publish(WorkerExchangeFiles.Kind.RESULT, identity, "[]"))
                .isInstanceOf(IOException.class);
        assertThat(files.read(WorkerExchangeFiles.Kind.RESULT, identity)).isEqualTo("{}");
    }

    private static Identity identity(long epoch) {
        return new Identity(1, 1, 1, 1, ProductionStage.DISCOVER, 1, epoch, CanonicalJson.sha256Hex("{}"), null);
    }
}
