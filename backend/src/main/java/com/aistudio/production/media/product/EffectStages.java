package com.aistudio.production.media.product;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.aistudio.production.contract.MediaEffectPort;
import com.aistudio.production.contract.MediaEffectPort.ExecutionProof;
import com.aistudio.production.contract.MediaEffectPort.Payload;
import com.aistudio.production.contract.MediaEffectPort.ProviderResult;
import com.aistudio.production.contract.MediaProcessRunner.Scratch;
import com.aistudio.production.contract.MediaProfile;
import com.aistudio.production.contract.MediaStageWork.Context;
import com.aistudio.production.contract.MediaStageWork.Item;
import com.aistudio.production.contract.MediaStageWork.Outcome;
import com.aistudio.production.contract.MediaStageWork.Result;
import com.aistudio.production.contract.MediaStore.Role;
import com.aistudio.production.contract.ProductionCostPort;
import com.aistudio.production.contract.ProductionPlanning;
import com.aistudio.production.contract.ProductionStage;

/**
 * IMAGE and TTS (D01/D02/D06): one simulated paid effect per scene, executed strictly in item order through the
 * shared cost port. Fixture presence and integrity are checked before reservation, so a missing fixture never
 * spends. The FAKE provider returns the pinned fixture; normalisation of the returned bytes happens inside the
 * provider callback, and a malformed result returns no payloads so the port settles it as EXECUTED_FAILED.
 * Items that already hold an operation are only recovered, never re-sent.
 */
@Component
class EffectStages {
    private static final Logger log = LoggerFactory.getLogger(EffectStages.class);
    static final String IMAGE_NORMALIZER = "image-normalize-1";
    static final String SPEECH_NORMALIZER = "speech-pcm48-1";

    private final StageSupport support;
    private final MediaEffectPort effects;

    EffectStages(StageSupport support, MediaEffectPort effects) {
        this.support = support;
        this.effects = effects;
    }

    Result run(Context context, boolean recover) {
        MediaProfile profile = support.profile(context);
        FixtureCatalog.Loaded catalog = null;
        List<ProductionPlanning.Scene> scenes = null;
        Map<String, String> script = null;
        for (Item item : context.items()) {
            if ("APPLIED".equals(item.state())) {
                continue;
            }
            if (context.cancelled().getAsBoolean()) {
                return new Result(Outcome.RETRY, "MEDIA_CANCELLED");
            }
            var key = new MediaEffectPort.Item(context.owner(), item.id());
            ProductionCostPort.EffectResult result = null;
            AtomicReference<String> failure = new AtomicReference<>();
            // B01: an item bound to an operation is first presented through recover, which never sends and needs no
            // fixture: stored evidence or a stored receipt is replayed, and an undispatched reservation of a
            // cancelled request or an ended budget period closes as NOT_EXECUTED with its reservation returned.
            // Only an ACTIVE item whose operation recover left sendable (a reservation that was never dispatched,
            // stopped before its dispatch permit) is then prepared and presented again through execute. That reuses
            // the same effect key and operation, and the shared permit re-checks cancel, period, lease and fencing:
            // it dispatches at most once and never re-sends an operation already dispatched or unknown. Items past
            // the execution proof are only recovered.
            if (item.operationId() != null) {
                result = effects.recover(key);
            }
            if (result == null || ("ACTIVE".equals(item.state())
                    && result.disposition() == ProductionCostPort.Disposition.RECOVERY_REQUIRED)) {
                var input = support.input(context, item);
                if (input.scene() == null) {
                    throw MediaFailure.blocked("MEDIA_INPUT_INTEGRITY", "Scene input is missing");
                }
                if (catalog == null) {
                    catalog = support.catalog(profile);
                    scenes = support.scenes(context, input);
                    script = context.stage() == ProductionStage.TTS ? support.script(context, input, scenes) : Map.of();
                }
                var scene = input.scene();
                if (!scenes.contains(scene)) {
                    throw MediaFailure.blocked("MEDIA_PLANNING_MISMATCH", "Frozen scene is not in the approved scenes");
                }
                result = context.stage() == ProductionStage.IMAGE
                        ? image(context, item, key, scene, catalog, failure)
                        : speech(context, item, key, scene, profile, catalog, script, failure);
            }
            switch (result.disposition()) {
                case RECEIPT -> {
                    effects.apply(key, result.receipt().operationId());
                    if (result.receipt().outcome() != ProductionCostPort.Outcome.RESULT_ADOPTED) {
                        String code = failure.get() != null ? failure.get()
                                : context.stage() == ProductionStage.IMAGE ? "MEDIA_IMAGE_FAILED" : "MEDIA_TTS_FAILED";
                        return new Result(Outcome.BLOCKED, code);
                    }
                }
                case REJECTED -> {
                    return new Result(item.operationId() == null ? Outcome.BLOCKED : Outcome.RECOVERY_REQUIRED,
                            "MEDIA_EFFECT_REJECTED");
                }
                default -> {
                    return new Result(Outcome.RECOVERY_REQUIRED, "MEDIA_EFFECT_PENDING");
                }
            }
        }
        return new Result(Outcome.APPLIED, null);
    }

    private ProductionCostPort.EffectResult image(Context context, Item item, MediaEffectPort.Item key,
            ProductionPlanning.Scene scene, FixtureCatalog.Loaded catalog, AtomicReference<String> failure) {
        String promptSha = FixtureCatalog.sha256(scene.imagePrompt().getBytes(StandardCharsets.UTF_8));
        var fixture = catalog.catalog().image(promptSha).orElseThrow(() -> MediaFailure.blocked(
                "MEDIA_IMAGE_FIXTURE_MISSING", "No image fixture for the scene prompt"));
        byte[] source = catalog.read(fixture.file(), fixture.sha256(), fixture.bytes());
        var price = support.settings().fakePriceKrw();
        return effects.execute(key, price, operationId -> {
            var proof = proof("image", operationId, catalog, fixture.id(), fixture.sha256(), price);
            try {
                var normalized = ImageNormalizer.normalize(source, fixture.mimeType(), RenderPlan.MAX_ZOOM);
                Map<String, Object> lineage = lineage(context, item, scene, catalog, fixture.id());
                lineage.put("promptSha256", promptSha);
                Map<String, Object> derived = new LinkedHashMap<>(lineage);
                derived.put("parentSha256", fixture.sha256());
                derived.put("normalizer", IMAGE_NORMALIZER);
                derived.put("normalization", normalized.report());
                List<Map<String, Object>> parents = List.of(Map.of("role", Role.SOURCE_IMAGE.name(),
                        "sha256", fixture.sha256()));
                return new ProviderResult(proof, List.of(
                        payload(Role.SOURCE_IMAGE, fixture.mimeType(), lineage,
                                FixtureCatalog.rightsRecord(fixture.rights(), fixture.sha256(), List.of()), source),
                        payload(Role.NORMALIZED_IMAGE, "image/png", derived,
                                FixtureCatalog.rightsRecord(fixture.rights(), fixture.sha256(), parents),
                                normalized.png())));
            } catch (MediaFailure ex) {
                log.warn("Image result for media item {} rejected: {}", item.id(), ex.code());
                failure.set(ex.code());
                return new ProviderResult(proof, List.of());
            } catch (RuntimeException ex) {
                // The simulated provider has executed; an unusable result settles as EXECUTED_FAILED, never unknown.
                log.error("Image result for media item {} could not be processed", item.id(), ex);
                failure.set("MEDIA_IMAGE_PROCESSING");
                return new ProviderResult(proof, List.of());
            }
        });
    }

    private ProductionCostPort.EffectResult speech(Context context, Item item, MediaEffectPort.Item key,
            ProductionPlanning.Scene scene, MediaProfile profile, FixtureCatalog.Loaded catalog,
            Map<String, String> script, AtomicReference<String> failure) {
        var fixture = catalog.catalog().narration(profile.voiceId(), profile.language(), scene.narration())
                .orElseThrow(() -> MediaFailure.blocked("MEDIA_TTS_FIXTURE_MISSING",
                        "No narration fixture for the scene text, voice and language"));
        byte[] source = catalog.read(fixture.file(), fixture.sha256(), fixture.bytes());
        Wav wav = Wav.parse(source);
        if (wav.sampleRate() != fixture.sampleRate()) {
            throw MediaFailure.blocked("MEDIA_TTS_FIXTURE_INVALID", "Narration fixture sample rate mismatch");
        }
        probe(context, item, source, wav);
        var price = support.settings().fakePriceKrw();
        return effects.execute(key, price, operationId -> {
            var proof = proof("speech", operationId, catalog, fixture.id(), fixture.sha256(), price);
            try {
                short[] pcm = Resampler.resample(wav.mono(), wav.sampleRate(), Alignment.RATE);
                byte[] pcmBytes = new Wav(Alignment.RATE, 1, pcm).encode();
                String pcmSha = FixtureCatalog.sha256(pcmBytes);
                Alignment alignment = Alignment.build(scene, script, fixture, fixture.sha256(), pcm, pcmSha);
                byte[] alignmentBytes = support.encode(alignment);
                Map<String, Object> lineage = lineage(context, item, scene, catalog, fixture.id());
                lineage.put("voiceId", profile.voiceId());
                lineage.put("language", profile.language());
                Map<String, Object> pcmLineage = new LinkedHashMap<>(lineage);
                pcmLineage.put("parentSha256", fixture.sha256());
                pcmLineage.put("normalizer", SPEECH_NORMALIZER);
                pcmLineage.put("sampleRate", Alignment.RATE);
                pcmLineage.put("sampleCount", pcm.length);
                pcmLineage.put("sourceSampleRate", wav.sampleRate());
                pcmLineage.put("sourceSampleCount", wav.frames());
                Map<String, Object> alignmentLineage = new LinkedHashMap<>(lineage);
                alignmentLineage.put("pcmSha256", pcmSha);
                alignmentLineage.put("timingSource", alignment.timingSource());
                var parent = List.<Map<String, Object>>of(Map.of("role", Role.SOURCE_AUDIO.name(),
                        "sha256", fixture.sha256()));
                return new ProviderResult(proof, List.of(
                        payload(Role.SOURCE_AUDIO, "audio/wav", lineage,
                                FixtureCatalog.rightsRecord(fixture.rights(), fixture.sha256(), List.of()), source),
                        payload(Role.PCM_AUDIO, "audio/wav", pcmLineage,
                                FixtureCatalog.rightsRecord(fixture.rights(), fixture.sha256(), parent), pcmBytes),
                        payload(Role.ALIGNMENT, "application/json", alignmentLineage,
                                FixtureCatalog.rightsRecord(fixture.rights(), fixture.sha256(), parent),
                                alignmentBytes)));
            } catch (MediaFailure ex) {
                log.warn("Speech result for media item {} rejected: {}", item.id(), ex.code());
                failure.set(ex.code());
                return new ProviderResult(proof, List.of());
            } catch (RuntimeException ex) {
                // The simulated provider has executed; an unusable result settles as EXECUTED_FAILED, never unknown.
                log.error("Speech result for media item {} could not be processed", item.id(), ex);
                failure.set("MEDIA_TTS_PROCESSING");
                return new ProviderResult(proof, List.of());
            }
        });
    }

    /** Cross-checks the decoded sample count of the fixture with ffprobe before any reservation. */
    private void probe(Context context, Item item, byte[] source, Wav wav) {
        var tools = MediaTools.open(support.runner(), new Scratch(context.owner().requestId(), item.id(),
                Math.max(1, context.attemptNo())), context.cancelled());
        tools.write("probe-source.wav", source);
        try {
            var result = tools.ffprobe(List.of("-v", "error", "-select_streams", "a:0", "-show_entries",
                    "stream=codec_name,sample_rate,channels,duration_ts", "-of", "json", "probe-source.wav"));
            var stream = support.json().readTree(result.output()).path("streams").path(0);
            if (!"pcm_s16le".equals(stream.path("codec_name").asString())
                    || stream.path("sample_rate").asString().isEmpty()
                    || Integer.parseInt(stream.path("sample_rate").asString()) != wav.sampleRate()
                    || stream.path("channels").asInt() != wav.channels()
                    || stream.path("duration_ts").asLong() != wav.frames()) {
                throw MediaFailure.blocked("MEDIA_TTS_FIXTURE_INVALID", "Probe sample count differs from the WAV data");
            }
        } finally {
            tools.delete("probe-source.wav");
        }
    }

    private Map<String, Object> lineage(Context context, Item item, ProductionPlanning.Scene scene,
            FixtureCatalog.Loaded catalog, String fixtureId) {
        Map<String, Object> lineage = new LinkedHashMap<>();
        lineage.put("schemaVersion", 1);
        lineage.put("stage", context.stage().name());
        lineage.put("itemId", item.id());
        lineage.put("inputSha256", item.inputHash());
        lineage.put("sceneId", scene.id());
        lineage.put("sceneOrder", scene.order());
        lineage.put("catalogId", catalog.catalog().catalogId());
        lineage.put("catalogSha256", catalog.catalogSha256());
        lineage.put("fixtureId", fixtureId);
        lineage.put("mode", "FAKE");
        return lineage;
    }

    private static ExecutionProof proof(String kind, long operationId, FixtureCatalog.Loaded catalog, String fixtureId,
            String fixtureSha, java.math.BigDecimal price) {
        return new ExecutionProof("fake-" + kind + "-" + operationId, "fixture-" + fixtureSha.substring(0, 32), price,
                "KRW", "FAKE fixture " + catalog.catalog().catalogId() + "/" + fixtureId + " (mock KRW)");
    }

    private Payload payload(Role role, String mime, Map<String, Object> lineage, Map<String, Object> rights,
            byte[] content) {
        return new Payload(role, mime, Math.max(1, Math.min(content.length, FixtureCatalog.MAX_FILE)),
                support.encodeText(lineage), support.encodeText(rights), () -> new ByteArrayInputStream(content));
    }
}
