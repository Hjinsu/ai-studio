package com.aistudio.production.api;

import java.net.URI;
import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aistudio.content.command.IdempotentCommands;
import com.aistudio.production.contract.ProductionDiscovery.EvaluatedCandidate;
import com.aistudio.production.contract.ProductionDiscovery.Mode;
import com.aistudio.production.contract.ProductionStatus;
import com.aistudio.security.CurrentActor;
import com.aistudio.shared.api.ApiId;

@RestController
@RequestMapping("/api/v1/production-requests")
class ProductionRequestController {
    record Create(@NotNull @Pattern(regexp = ApiId.PATTERN) String channelId,
            @NotNull @Pattern(regexp = "SHORTS|LONGFORM") String contentType,
            Mode selectionMode, @Min(5) @Max(10) Integer candidateCount) {}

    record Choose(@NotNull @Min(1) @Max(10) Integer candidateId, @NotNull @Min(0) Integer version,
            @NotNull @Pattern(regexp = "[0-9a-f]{64}") String batchToken) {}

    record Discovery(Mode selectionMode, int candidateCount, int version, String batchToken, boolean awaitingSelection,
            List<EvaluatedCandidate> candidates, Integer chosenCandidateId, Integer selectedCandidateId,
            String selectionReason) {}

    record Summary(String id, ProductionStatus status) {}

    record Detail(String id, String channelId, String contentType, ProductionStatus status,
            String contentId, String runId, Instant cancelRequestedAt, String limitKrw, String reservedKrw,
            String recognizedKrw, List<ProductionRequestMapper.Step> steps) {}

    private final ProductionRequestService service;

    ProductionRequestController(ProductionRequestService service) {
        this.service = service;
    }

    @PostMapping
    ResponseEntity<Summary> create(
            @RequestHeader("Idempotency-Key") @Pattern(regexp = IdempotentCommands.KEY_PATTERN) String key,
            @Valid @RequestBody Create body) {
        var result = service.create(CurrentActor.principal().id(), ApiId.parse("channelId", body.channelId()),
                body.contentType(), key, body.selectionMode(), body.candidateCount());
        String id = Long.toString(result.request().id());
        Summary summary = new Summary(id, result.request().status());
        return result.replayed() ? ResponseEntity.ok(summary)
                : ResponseEntity.created(URI.create("/api/v1/production-requests/" + id)).body(summary);
    }

    @GetMapping("/{id}")
    Detail get(@PathVariable @Pattern(regexp = ApiId.PATTERN) String id) {
        return service.get(CurrentActor.principal().id(), ApiId.parse("id", id));
    }

    @PostMapping("/{id}/cancel")
    Detail cancel(@PathVariable @Pattern(regexp = ApiId.PATTERN) String id) {
        return service.cancel(CurrentActor.principal().id(), ApiId.parse("id", id));
    }

    @GetMapping("/{id}/discovery")
    Discovery discovery(@PathVariable @Pattern(regexp = ApiId.PATTERN) String id) {
        return service.discovery(CurrentActor.principal().id(), ApiId.parse("id", id));
    }

    @PostMapping("/{id}/selection")
    Discovery choose(@PathVariable @Pattern(regexp = ApiId.PATTERN) String id, @Valid @RequestBody Choose choice) {
        return service.choose(CurrentActor.principal().id(), ApiId.parse("id", id), choice.candidateId(),
                choice.version(), choice.batchToken());
    }
}
