import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("java")
    id("org.springframework.boot") version "3.5.4"
    id("io.spring.dependency-management") version "1.1.7"
    id("io.freefair.lombok") version "8.14"
    id("com.github.ben-manes.versions") version "0.52.0"
    id("org.flywaydb.flyway") version "11.11.0"
    kotlin("jvm") version "2.2.10"
    kotlin("plugin.spring") version "2.2.10"
    kotlin("plugin.jpa") version "2.2.10"
    kotlin("plugin.allopen") version "2.2.10"
}

allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.Embeddable")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("lombok.Data")
}


group = "ch.uzh.ifi"
version = "0.0.3"
java.sourceCompatibility = JavaVersion.VERSION_21

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-mail")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-data-rest")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-cache")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.restdocs:spring-restdocs-mockmvc")
    testImplementation("org.springframework.security:spring-security-test")
    implementation("org.springframework.security:spring-security-data:6.5.2")
    implementation("org.springframework.security:spring-security-oauth2-jose:6.5.2")
    implementation("org.springdoc:springdoc-openapi-ui:1.8.0")
    implementation("org.springdoc:springdoc-openapi-security:1.8.0")
    runtimeOnly("org.postgresql:postgresql")
    implementation("org.modelmapper:modelmapper:3.2.4")
    implementation("org.apache.commons:commons-text:1.14.0")
    implementation("org.apache.commons:commons-math3:3.6.1")
    implementation("org.apache.commons:commons-collections4:4.5.0")
    implementation("org.keycloak:keycloak-admin-client:22.0.1")
    implementation("io.projectreactor.netty:reactor-netty-http")
    implementation("com.github.docker-java:docker-java:3.5.3")
    implementation("com.github.docker-java:docker-java-transport-httpclient5:3.5.3")
    implementation("org.eclipse.jgit:org.eclipse.jgit:7.3.0.202506031305-r")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-toml:2.19.2")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
    implementation("org.apache.httpcomponents:httpcore:4.4.16")
    implementation("org.apache.httpcomponents:httpclient:4.5.14")
    implementation("org.apache.httpcomponents.client5:httpclient5:5.5")
    implementation("org.apache.tika:tika-core:3.2.2")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("io.github.oshai:kotlin-logging-jvm:7.0.12")
    implementation("com.github.haifengl:smile-core:3.0.0")
    implementation("org.bytedeco:arpack-ng-platform:3.9.1-1.5.12")
    implementation("org.bytedeco:openblas-platform:0.3.30-1.5.12")
    implementation("org.bytedeco:javacpp-platform:1.5.12")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.springframework.boot:spring-boot-starter-test") {
        exclude("org.mockito", "mockito-core")
    }
    testImplementation("org.springframework.boot:spring-boot-starter-test") {
        exclude("org.junit.vintage", "junit-vintage-engine")
        exclude("junit", "junit")
    }
    testImplementation("com.ninja-squad:springmockk:4.0.2")
    testImplementation("org.junit.platform:junit-platform-suite:1.13.4")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.13.4")
}

/**
 * Build and push a Docker image of the backend to DockerHub via the built-in gradle task.
 * Requires passing 2 environment variables:
 * (1) username     - username to utilize for logging into DockerHub
 * (2) password     - password to utilize for logging into DockerHub
 * These are already defined in the deployment environment (as GitHub Actions secrets).
 * For usage see the deployment script under .github/workflows/backend.yml.
 */

tasks.getByName<org.springframework.boot.gradle.tasks.bundling.BootBuildImage>("bootBuildImage") {
    if (project.hasProperty("username")) {
        // tiny is not an option because smile needs libstdc++.so
        builder.set("paketobuildpacks/builder-jammy-base")
        imageName.set("sealuzh/access-backend:x")
        publish.set(true)
        docker.publishRegistry.username.set(project.property("username") as String)
        docker.publishRegistry.password.set(project.property("password") as String)
    }
}

tasks.withType<KotlinCompile> {
    compilerOptions {
        freeCompilerArgs = listOf("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    filter {
        includeTestsMatching("ch.uzh.ifi.access.AllTests")
        includeTestsMatching("ch.uzh.ifi.access.PerformanceTests")
    }
    testLogging {
        events("passed", "skipped", "failed")
    }
}

flyway {
    url = "jdbc:postgresql://localhost:5432/access"
    user = "admin"
    password = "admin"
}
