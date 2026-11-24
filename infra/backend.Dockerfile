# 빌드 컨텍스트는 저장소 루트. 이미지 버전은 docs/16_SOURCES.md 기준으로 고정한다.
FROM eclipse-temurin:21.0.13_9-jdk-noble AS build
WORKDIR /workspace
COPY gradlew settings.gradle.kts gradle.properties ./
COPY gradle gradle
COPY backend/build.gradle.kts backend/
RUN chmod +x gradlew && ./gradlew --no-daemon :backend:dependencies > /dev/null
COPY backend/src backend/src
RUN ./gradlew --no-daemon :backend:bootJar

FROM eclipse-temurin:21.0.13_9-jre-noble
RUN groupadd --system app && useradd --system --gid app --no-create-home app
WORKDIR /app
COPY --from=build /workspace/backend/build/libs/*.jar app.jar
USER app
EXPOSE 8080
ENV SPRING_PROFILES_ACTIVE=docker
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
