package com.aistudio.shared.api;

import java.util.List;
import java.util.function.Function;

/**
 * 목록 응답 {items, page, size, totalElements, totalPages} (docs/api/openapi.yaml PageMeta).
 */
public record PageResponse<T>(List<T> items, int page, int size, long totalElements, int totalPages) {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    public static <S, T> PageResponse<T> of(List<S> rows, PageQuery query, long total, Function<S, T> mapper) {
        int totalPages = (int) ((total + query.size() - 1) / query.size());
        return new PageResponse<>(rows.stream().map(mapper).toList(), query.page(), query.size(), total, totalPages);
    }

    /** 0부터 시작하는 page와 1~100 size. 범위 검사는 컨트롤러 파라미터 검증에서 한다. */
    public record PageQuery(int page, int size) {

        public long offset() {
            return (long) page * size;
        }
    }
}
