package com.aistudio.content.command;

import java.util.function.Function;
import java.util.function.LongSupplier;

import org.slf4j.MDC;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import com.aistudio.audit.AuditService.Actor;
import com.aistudio.shared.api.ApiException;
import com.aistudio.shared.api.ErrorCode;
import com.aistudio.shared.db.LockRetryingTransactions;

/**
 * queue_command_log 기반 작성 명령 멱등성 (T03 API 계약, Idempotency-Key). 키 공간은 전역이다.
 * <ul>
 * <li>명령 효과와 로그 INSERT를 한 최상위 READ COMMITTED 트랜잭션에 커밋한다(교착·잠금 시간초과는 전체 재시도).</li>
 * <li>같은 키·같은 지문은 현재 리소스로 재생(200), 다른 지문은 409 RESOURCE_CONFLICT.</li>
 * <li>동시 같은 키 요청은 UNIQUE(request_key) 또는 선행 요청의 효과로 실패한 뒤 롤백·재조회로 재생한다.</li>
 * </ul>
 */
@Component
public class IdempotentCommands {

    public static final String KEY_PATTERN = "^[A-Za-z0-9:._-]{8,128}$";

    public record Command(String requestKey, String commandType, String targetType, String fingerprint,
            Actor actor) {
    }

    /** 대상 리소스 ID와 재생 여부(재생이면 200, 신규면 201). */
    public record Result(long targetId, boolean replayed) {
    }

    private final CommandLogMapper mapper;
    private final LockRetryingTransactions transactions;

    IdempotentCommands(CommandLogMapper mapper, LockRetryingTransactions transactions) {
        this.mapper = mapper;
        this.transactions = transactions;
    }

    /**
     * @param effect      트랜잭션 안에서 실행되는 명령 효과. 대상 ID를 반환한다
     * @param onDuplicate 같은 키 재생이 아닌 UNIQUE 위반을 API 오류로 바꾼다(null이면 그대로 전파)
     */
    public Result execute(Command command, LongSupplier effect,
            Function<DuplicateKeyException, RuntimeException> onDuplicate) {
        if (command.requestKey() == null || !command.requestKey().matches(KEY_PATTERN)) {
            throw ApiException.validation("Idempotency-Key", "must match " + KEY_PATTERN);
        }
        CommandLogMapper.CommandLog existing = mapper.findByKey(command.requestKey());
        if (existing != null) {
            return replay(existing, command);
        }
        try {
            long targetId = transactions.execute(() -> {
                long id = effect.getAsLong();
                mapper.insert(new CommandLogMapper.CommandLog(null, command.requestKey(), command.fingerprint(),
                        command.commandType(), command.targetType(), id, command.actor().userId(), MDC.get("traceId"),
                        null));
                return id;
            });
            return new Result(targetId, false);
        } catch (DuplicateKeyException e) {
            CommandLogMapper.CommandLog raced = mapper.findByKey(command.requestKey());
            if (raced != null) {
                return replay(raced, command);
            }
            throw onDuplicate == null ? e : onDuplicate.apply(e);
        } catch (ApiException e) {
            // 같은 키의 선행 요청이 먼저 커밋해 상태 검사(예: baseRevisionNo)가 실패한 경우 재생한다
            CommandLogMapper.CommandLog raced = mapper.findByKey(command.requestKey());
            if (raced != null) {
                return replay(raced, command);
            }
            throw e;
        }
    }

    private static Result replay(CommandLogMapper.CommandLog existing, Command command) {
        if (!existing.requestFingerprint().equals(command.fingerprint())
                || !existing.commandType().equals(command.commandType())) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT,
                    "The Idempotency-Key was already used with a different request.");
        }
        return new Result(existing.targetId(), true);
    }

    /** UNIQUE 위반이 지정한 제약 이름 때문인지 확인한다(MariaDB 오류 메시지에 키 이름이 포함된다). */
    public static boolean violates(DuplicateKeyException e, String constraintName) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause.getMessage() != null && cause.getMessage().contains(constraintName)) {
                return true;
            }
        }
        return false;
    }
}
