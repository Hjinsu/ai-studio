-- 기존 설치 MariaDB 11.8에 AI Studio 전용 DB와 계정을 만든다 (ADR-010). 다른 DB는 변경하지 않는다.
-- 실행: mariadb -u root -p 로 접속 후 아래 문장을 붙여 넣는다. '<CHANGE_ME>'는 .env의 AI_STUDIO_DB_PASSWORD 값으로 바꾸어 입력하고,
-- 비밀번호를 넣은 사본은 저장소에 저장하거나 커밋하지 않는다.
CREATE DATABASE IF NOT EXISTS ai_studio CHARACTER SET utf8mb4;
CREATE USER IF NOT EXISTS 'ai_studio'@'localhost' IDENTIFIED BY '<CHANGE_ME>';
-- 최소 권한: ai_studio DB 한정 DML + Flyway DDL (docs/13_SECURITY.md)
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, DROP, INDEX, REFERENCES,
      CREATE VIEW, SHOW VIEW, TRIGGER, CREATE TEMPORARY TABLES, LOCK TABLES
    ON ai_studio.* TO 'ai_studio'@'localhost';
