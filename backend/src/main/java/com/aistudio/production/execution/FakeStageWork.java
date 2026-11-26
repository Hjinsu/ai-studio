package com.aistudio.production.execution;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;

import com.aistudio.production.contract.ProductionCostPort;
import com.aistudio.production.contract.ProductionSelectionPort;
import com.aistudio.production.contract.ProductionStage;

/**
 * V2-01 FAKE stage work. Stages that will call paid providers go through the cost port with a FAKE priced
 * request; subtitle and render are local. Produces no media and must never be treated as a finished product.
 *
 * <p>SELECT picks its target length inside the length range of the channel configuration the request froze at
 * creation, for the request's content type (SHORTS or LONGFORM). A later change to the channel's settings does
 * not move it. No LONGFORM media is produced: LONGFORM only gets a valid FAKE selection.
 */
public class FakeStageWork implements StageWork {
    static final String FAKE_SCENARIO = "SUCCESS";
    /** Kept whenever the frozen range allows it, so earlier SHORTS selections replay with the same input hash. */
    static final int FAKE_DURATION_SECONDS = 45;
    private static final Set<ProductionStage> LOCAL = EnumSet.of(ProductionStage.SUBTITLE, ProductionStage.RENDER);

    /** A content type's allowed target length, in whole seconds, both ends inclusive. */
    public record DurationRange(int minSeconds, int maxSeconds) {
        public DurationRange {
            if (minSeconds < 1 || minSeconds > maxSeconds) {
                throw new IllegalArgumentException("A length range needs 1 <= min <= max seconds");
            }
        }

        /**
         * The FAKE target: {@value FakeStageWork#FAKE_DURATION_SECONDS} s when allowed, otherwise the midpoint
         * (rounded down). Depends only on the range, so a replay of the same request picks the same length.
         */
        public int target() {
            if (minSeconds <= FAKE_DURATION_SECONDS && FAKE_DURATION_SECONDS <= maxSeconds) {
                return FAKE_DURATION_SECONDS;
            }
            return minSeconds + (maxSeconds - minSeconds) / 2;
        }
    }

    /** Resolves the length range a request froze for its content type. Must be deterministic per request. */
    @FunctionalInterface
    public interface DurationRanges {
        DurationRange range(StepContext context);
    }

    private final BigDecimal effectMaxKrw;
    private final DurationRanges ranges;

    public FakeStageWork(BigDecimal effectMaxKrw, DurationRanges ranges) {
        if (effectMaxKrw == null || effectMaxKrw.signum() <= 0) {
            throw new IllegalArgumentException("FAKE effect maximum must be a positive KRW amount");
        }
        this.effectMaxKrw = effectMaxKrw;
        this.ranges = Objects.requireNonNull(ranges, "FAKE length ranges are required");
    }

    /** Executor tests only: a fixed {@value #FAKE_DURATION_SECONDS} s range that ignores channel settings. */
    FakeStageWork(BigDecimal effectMaxKrw) {
        this(effectMaxKrw, context -> new DurationRange(FAKE_DURATION_SECONDS, FAKE_DURATION_SECONDS));
    }

    /**
     * Reads the range from the channel configuration revision the request froze ({@code channel_config_id}),
     * never from the channel's latest settings. Read-only.
     */
    public static DurationRanges frozenRanges(JdbcTemplate jdbc) {
        Objects.requireNonNull(jdbc, "jdbc");
        return context -> {
            List<DurationRange> found = jdbc.query("""
                    SELECT r.content_type, c.shorts_min_seconds, c.shorts_max_seconds,
                           c.longform_min_seconds, c.longform_max_seconds
                    FROM production_request r
                    JOIN channel_config c ON c.channel_id = r.channel_id AND c.id = r.channel_config_id
                    WHERE r.id = ? AND r.channel_id = ?
                    """, (row, n) -> switch (row.getString("content_type")) {
                        case "SHORTS" -> new DurationRange(row.getInt("shorts_min_seconds"),
                                row.getInt("shorts_max_seconds"));
                        case "LONGFORM" -> new DurationRange(row.getInt("longform_min_seconds"),
                                row.getInt("longform_max_seconds"));
                        default -> throw new StepFailure("FAKE_CONTENT_TYPE_UNSUPPORTED", false,
                                "Unsupported content type");
                    }, context.requestId(), context.channelId());
            if (found.size() != 1) {
                throw new StepFailure("FAKE_LENGTH_RANGE_MISSING", false,
                        "The request's frozen channel configuration was not found");
            }
            return found.get(0);
        };
    }

    @Override
    public Kind kind(ProductionStage stage) {
        if (stage == ProductionStage.SELECT) {
            return Kind.SELECT;
        }
        return LOCAL.contains(stage) ? Kind.LOCAL : Kind.EFFECT;
    }

    @Override
    public String runLocal(StepContext context) {
        return "fake:" + context.stage().name().toLowerCase(Locale.ROOT) + ":" + context.requestId();
    }

    @Override
    public ProductionCostPort.PricedRequest price(StepContext context) {
        return new ProductionCostPort.PricedRequest("KRW", effectMaxKrw, FAKE_SCENARIO);
    }

    @Override
    public ProductionSelectionPort.Selection select(StepContext context) {
        DurationRange range = ranges.range(context);
        if (range == null) {
            throw new StepFailure("FAKE_LENGTH_RANGE_MISSING", false, "No length range for the request");
        }
        String fingerprint = EffectRequestHash.sha256("fake-selection:v1:" + context.requestId());
        return new ProductionSelectionPort.Selection(fingerprint, "FAKE topic " + context.requestId(),
                "FAKE brief for fixed-stage executor verification", range.target());
    }
}
