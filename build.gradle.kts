import net.ltgt.gradle.errorprone.CheckSeverity
import net.ltgt.gradle.errorprone.errorprone
import net.ltgt.gradle.nullaway.nullaway

plugins {
    java
    jacoco
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
    id("com.diffplug.spotless") version "7.0.4"
    id("net.ltgt.errorprone") version "5.1.1"
    id("net.ltgt.nullaway") version "3.2.0"
}

group = "bo.coprop"
version = "0.0.1-SNAPSHOT"
description = "COPROP - API y reglas de negocio"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

extra["springModulithVersion"] = "2.1.1"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-mail")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-api:3.1.1")
    implementation("org.springframework.modulith:spring-modulith-observability-api")
    implementation("org.springframework.modulith:spring-modulith-starter-core")
    implementation("org.springframework.modulith:spring-modulith-starter-jpa")
    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("org.springframework.modulith:spring-modulith-actuator")
    runtimeOnly("org.springframework.modulith:spring-modulith-observability-core")
    runtimeOnly("org.springframework.modulith:spring-modulith-runtime")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")
    testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
    testImplementation("org.springframework.boot:spring-boot-starter-mail-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.springframework.modulith:spring-modulith-starter-test")
    testImplementation("org.springframework.modulith:spring-modulith-docs")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    errorprone("com.google.errorprone:error_prone_core:2.39.0")
    errorprone("com.uber.nullaway:nullaway:0.12.7")
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.modulith:spring-modulith-bom:${property("springModulithVersion")}")
    }
}

spotless {
    java {
        target("src/**/*.java")
        palantirJavaFormat("2.68.0")
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    // El reporte de cobertura se genera siempre tras los tests, para que CI no tenga que
    // invocar dos tareas ni acordarse del orden.
    finalizedBy(tasks.jacocoTestReport)
}

// El CSV lo suma el workflow de CI para publicar la cobertura, porque una columna se agrega con
// awk sin depender de un parser de XML. El HTML es para leerlo a mano.
tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        csv.required = true
        html.required = true
        xml.required = false
    }
}

// Analisis estatico. Spotless, arriba, solo ordena el formato; estos dos miran lo que el codigo
// hace: Error Prone busca defectos conocidos y NullAway persigue los NullPointerException.
//
// Error Prone se queda en 2.39.0 y NO sube a la ultima. La 2.50.0 elimino
// com.google.errorprone.predicates.type.DescendantOf, que NullAway 0.12.7 todavia usa, y el
// compilador revienta con NoClassDefFoundError al arrancar el analisis. Antes de subir Error
// Prone hay que comprobar que la version de NullAway lo soporte.
nullaway {
    // Dentro de bo.coprop un tipo sin @Nullable no admite null, y NullAway lo comprueba. Fuera
    // --Spring, el JDK-- se guia por las anotaciones que traiga cada libreria.
    //
    // Se elige esto en vez del modo JSpecify (onlyNullMarked) porque cubre todo el codigo desde
    // el primer dia sin tener que anotar cada package-info. Migrar a JSpecify mas adelante es
    // posible y no urge.
    annotatedPackages.add("bo.coprop")
}

tasks.withType<JavaCompile>().configureEach {
    options.errorprone {
        disableWarningsInGeneratedCode = true
        // Un aviso que nadie mira no es analisis estatico: NullAway rompe el build, no avisa.
        nullaway { severity = CheckSeverity.ERROR }
    }
}

// Lo que se decidio NO activar, y por que:
//
// -Werror, que convertiria todo aviso en error. Los checks de Error Prone con severidad ERROR ya
// rompen el build, que es donde estan los defectos de verdad; -Werror arrastraria ademas los
// avisos de javac, y entonces una deprecacion al subir de version de Spring dejaria el proyecto
// sin compilar por algo que no es un defecto. Los avisos de Error Prone se leen en la salida del
// build y se corrigen, pero no bloquean.

// El build falla si el formato no esta aplicado. `./gradlew spotlessApply` lo corrige.
tasks.named("check") {
    dependsOn("spotlessCheck")
}
