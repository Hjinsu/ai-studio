package com.aistudio.shared.api;

/**
 * 오류 상세 항목. field는 입력 필드 경로이며 해당 없으면 null.
 */
public record ErrorDetail(String field, String reason) {
}
