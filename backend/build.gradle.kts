import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    java
    alias(libs.plugins.spring.boot)
}

group = "com.aistudio"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    // 버전은 Spring Boot BOM이 관리하고, BOM 밖 의존성만 version catalog에서 고정한다
    implementation(platform(SpringBootPlugin.BOM_COORDINATES))
    testImplementation(platform(SpringBootPlugin.BOM_COORDINATES))

    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("com.github.ben-manes.caffeine:caffeine")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation(libs.mybatis.spring.boot.starter)
    implementation("org.flywaydb:flyway-mysql")
    runtimeOnly("org.mariadb.jdbc:mariadb-java-client")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    // OpenAPI 계약 검증은 테스트 classpath 전용(ADR-041)
    testImplementation(libs.openapi.request.validator.mockmvc)
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-mariadb")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    systemProperty("user.timezone", "UTC")
    systemProperty("file.encoding", "UTF-8")
    // 계약 테스트가 읽는 OpenAPI 명세 변경 시 테스트를 다시 실행한다(up-to-date 캐시로 건너뛰지 않음)
    inputs.file(rootProject.file("docs/api/openapi.yaml")).withPathSensitivity(PathSensitivity.RELATIVE)
    // 운영 권한 템플릿(ProductionGrantsIT가 그대로 적용)
    inputs.file(rootProject.file("infra/mariadb/prod-app-grants.sql")).withPathSensitivity(PathSensitivity.RELATIVE)
}

// 단위 테스트: Docker/DB 불필요
tasks.named<Test>("test") {
    useJUnitPlatform {
        excludeTags("integration")
    }
}

// 통합 테스트: Testcontainers MariaDB, Docker 필요 (ADR-011, CI Linux job)
tasks.register<Test>("integrationTest") {
    description = "Runs integration tests that require Docker."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeTags("integration")
    }
    shouldRunAfter(tasks.named("test"))
    // AdminCliJarIT: 실제 bootJar로 관리자 CLI 실행 경로를 검증한다
    val bootJar = tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar")
    dependsOn(bootJar)
    inputs.file(bootJar.flatMap { it.archiveFile }).withPathSensitivity(PathSensitivity.NONE)
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf("-Dai-studio.boot-jar=" + bootJar.get().archiveFile.get().asFile.absolutePath)
    })
}

tasks.named<Jar>("jar") {
    enabled = false
}
