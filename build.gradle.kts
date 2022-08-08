plugins {
    kotlin("jvm") version "1.6.21"
}

group = "fr.corpauration"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    mavenLocal()
    flatDir {
        dirs("annotations")
    }
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.6.21")
    implementation("org.jetbrains.kotlin:kotlin-reflect:1.6.21")
    implementation("io.quarkus:quarkus-reactive-pg-client:2.9.2.Final")
    implementation("com.squareup:kotlinpoet:1.12.0")
    implementation("com.google.devtools.ksp:symbol-processing-api:1.6.21-1.0.6")
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions.jvmTarget = JavaVersion.VERSION_11.toString()
    kotlinOptions.javaParameters = true
}