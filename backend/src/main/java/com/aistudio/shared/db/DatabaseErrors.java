package com.aistudio.shared.db;

import java.sql.SQLException;

/**
 * MariaDB 오류 판별. MariaDB 11.6+는 innodb_snapshot_isolation=ON이 기본이라 REPEATABLE READ 트랜잭션이 다른
 * 트랜잭션이 바꾼 행을 쓰려 하면 중복키(1062) 대신 1020("Record has changed since last read")을 낸다.
 * 동시성 충돌로 취급한다(2026-11-25 MariaDB 11.8.2에서 확인).
 */
public final class DatabaseErrors {

    public static final int SNAPSHOT_CONFLICT = 1020;

    private DatabaseErrors() {
    }

    public static boolean isSnapshotConflict(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getErrorCode() == SNAPSHOT_CONFLICT) {
                return true;
            }
        }
        return false;
    }
}
