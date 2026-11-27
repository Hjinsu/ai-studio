package com.aistudio.production.media.product;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Fallback;
import org.springframework.stereotype.Component;

import com.aistudio.production.contract.MediaStageWork;
import com.aistudio.shared.api.ApiException;

/**
 * V2-04 product media worker for the {@code v2-media-1} pipeline. Each fixed stage is delegated to its product
 * implementation; cost, lease and file finalisation stay in the shared ports. A stage either applies, asks for a
 * retry (transient local fault, new attempt), blocks (integrity, rights, profile or QC violation) or reports that a
 * paid effect needs recovery. Registered as a fallback so an explicitly provided worker (tests) takes precedence.
 */
@Component
@Fallback
class ProductMediaWork implements MediaStageWork {
    private static final Logger log = LoggerFactory.getLogger(ProductMediaWork.class);

    private final EffectStages effects;
    private final SubtitleStage subtitles;
    private final RenderStage render;
    private final MetadataStage metadata;
    private final QcStage qc;

    ProductMediaWork(EffectStages effects, SubtitleStage subtitles, RenderStage render, MetadataStage metadata,
            QcStage qc) {
        this.effects = effects;
        this.subtitles = subtitles;
        this.render = render;
        this.metadata = metadata;
        this.qc = qc;
    }

    @Override
    public Result execute(Context context) {
        return run(context, false);
    }

    @Override
    public Result recover(Context context) {
        return run(context, true);
    }

    private Result run(Context context, boolean recover) {
        boolean paid = switch (context.stage()) {
            case IMAGE, TTS -> true;
            default -> false;
        };
        try {
            if (context.items().isEmpty()) {
                return new Result(Outcome.BLOCKED, "MEDIA_INPUT_INCOMPLETE");
            }
            return switch (context.stage()) {
                case IMAGE, TTS -> effects.run(context, recover);
                case SUBTITLE -> subtitles.run(context);
                case RENDER -> render.run(context);
                case METADATA -> metadata.run(context);
                case QC -> qc.run(context);
                default -> new Result(Outcome.BLOCKED, "MEDIA_STAGE_UNSUPPORTED");
            };
        } catch (MediaFailure ex) {
            log.warn("Media {} step for request {} stopped: {}", context.stage(), context.owner().requestId(),
                    ex.getMessage(), ex.getCause());
            return new Result(ex.kind() == MediaFailure.Kind.RETRY ? Outcome.RETRY : Outcome.BLOCKED, ex.code());
        } catch (tools.jackson.core.JacksonException ex) {
            log.warn("Media {} step for request {} read malformed JSON", context.stage(), context.owner().requestId());
            return new Result(paid ? Outcome.RECOVERY_REQUIRED : Outcome.BLOCKED, "MEDIA_JSON_INVALID");
        } catch (ApiException ex) {
            log.warn("Media {} step for request {} hit a shared-port refusal: {}", context.stage(),
                    context.owner().requestId(), ex.errorCode());
            return new Result(paid ? Outcome.RECOVERY_REQUIRED : Outcome.RETRY, "MEDIA_PORT_" + ex.errorCode().name());
        } catch (IllegalArgumentException ex) {
            log.warn("Media {} step for request {} produced invalid payload metadata", context.stage(),
                    context.owner().requestId(), ex);
            return new Result(paid ? Outcome.RECOVERY_REQUIRED : Outcome.BLOCKED, "MEDIA_PAYLOAD_INVALID");
        } catch (IllegalStateException ex) {
            log.warn("Media {} step for request {} lost its fence: {}", context.stage(), context.owner().requestId(),
                    ex.getMessage());
            return new Result(paid ? Outcome.RECOVERY_REQUIRED : Outcome.RETRY, "MEDIA_FENCE_LOST");
        }
    }
}
