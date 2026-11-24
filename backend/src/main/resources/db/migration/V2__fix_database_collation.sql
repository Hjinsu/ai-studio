-- ADR-014: DB 기본 collation을 utf8mb4_unicode_ci로 확정한다.
-- V1(collation 미지정)이 MariaDB 11.8 기본 utf8mb4_uca1400_ai_ci를 적용한 것을 바로잡는다. V1은 수정하지 않는다(ADR-009).
-- 주의: MariaDB 11.2+는 CHARSET만 지정하고 COLLATE를 생략하면 DB 기본값이 아닌 character_set_collations(uca1400)를 쓴다.
-- 이후 테이블 DDL은 CHARSET/COLLATE를 모두 생략하거나 COLLATE=utf8mb4_unicode_ci를 명시한다 (docs/03_DATABASE.md).
ALTER DATABASE CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
