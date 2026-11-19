import net.ltgt.gradle.errorprone.errorprone

plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.spotless)
    alias(libs.plugins.errorprone)
    alias(libs.plugins.protobuf)
}

group = "ru.tinvest.trainer"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.modulith:spring-modulith-bom:${libs.versions.springModulith.get()}")
    }
}

dependencies {
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.starter.flyway)
    implementation(libs.spring.modulith.starter.core)
    implementation(libs.flyway.database.postgresql)
    runtimeOnly(libs.postgresql)
    implementation(libs.protobuf.java)
    implementation(libs.grpc.protobuf)
    implementation(libs.grpc.stub)
    implementation(libs.fastexcel)
    runtimeOnly(libs.grpc.netty.shaded)
    // javax.annotation.Generated для grpc-стабов (рекомендация grpc-java для Java 9+)
    compileOnly(libs.tomcat.annotations.api)

    errorprone(libs.errorprone.core)

    testImplementation(libs.grpc.inprocess)
    testImplementation(libs.fastexcel.reader)
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.security.test)
    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.spring.modulith.starter.test)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    testRuntimeOnly(libs.junit.platform.launcher)
}

protobuf {
    protoc { artifact = libs.protobuf.protoc.get().toString() }
    plugins {
        create("grpc") { artifact = "${libs.grpc.protoc.gen.get()}:${osdetector.classifier}@exe" }
    }
}

sourceSets {
    main {
        proto { srcDir("proto") }
    }
    // Сквозной сценарий с настоящим воркером ядра (backend#4): отдельный набор, в `build` не входит
    create("e2eTest") {
        compileClasspath += sourceSets.main.get().output
        runtimeClasspath += sourceSets.main.get().output
    }
}

configurations["e2eTestImplementation"].extendsFrom(configurations.testImplementation.get())
configurations["e2eTestRuntimeOnly"].extendsFrom(configurations.testRuntimeOnly.get())

spotless {
    java {
        palantirJavaFormat(libs.versions.palantirJavaFormat.get())
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
        targetExclude("build/**", "**/build/generated/**")
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-parameters")
    options.errorprone {
        disableWarningsInGeneratedCode.set(true)
        // protoc не ставит @Generated на сообщения — исключаем по пути
        excludedPaths.set(".*/build/generated/.*")
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

// ./gradlew e2eTest — нужны Docker и образ воркера (`simulation-worker`, собирается из core, см. AGENTS.md)
tasks.register<Test>("e2eTest") {
    description = "Сквозной сценарий и сверка денег с настоящим воркером ядра (Testcontainers)"
    group = "verification"
    testClassesDirs = sourceSets["e2eTest"].output.classesDirs
    classpath = sourceSets["e2eTest"].runtimeClasspath
    // образ воркера и пр. передаются системными свойствами
    systemProperty("e2e.worker.image", System.getProperty("e2e.worker.image") ?: "simulation-worker:latest")
    // результат не кэшируется: тест ходит во внешние контейнеры
    outputs.upToDateWhen { false }
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = true
    }
    shouldRunAfter(tasks.test)
}
