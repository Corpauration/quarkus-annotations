plugins {
    kotlin("jvm") version "1.9.0"
    id("maven-publish")
}

group = "fr.corpauration"
version = "1.1.1"

repositories {
    mavenCentral()
    mavenLocal()
    flatDir {
        dirs("annotations")
    }
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.0")
    implementation("org.jetbrains.kotlin:kotlin-reflect:1.9.0")
    implementation("io.quarkus:quarkus-reactive-pg-client:2.16.9.Final")
    implementation("com.google.devtools.ksp:symbol-processing-api:1.9.0-1.0.11")
}

tasks.test {
    useJUnitPlatform()
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions.jvmTarget = JavaVersion.VERSION_17.toString()
    kotlinOptions.javaParameters = true
}

val sourcesJar by tasks.creating(Jar::class) {
    archiveClassifier.set("sources")
    from(sourceSets.getByName("main").allSource)
}

publishing {
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/corpauration/quarkus-annotations")
            credentials {
                username = System.getenv("USERNAME")
                password = System.getenv("GH_TOKEN")
            }
        }
    }
    publications {
        register("gprRelease", MavenPublication::class) {
            from(components["java"])

            artifact(sourcesJar)

            pom {
                packaging = "jar"
                name.set("quarkus-annotations")
                description.set("Add annotations to quarkus app")
                url.set("https://github.com/Corpauration/quarkus-annotations")
                scm {
                    url.set("https://github.com/Corpauration/quarkus-annotations")
                }
                issueManagement {
                    url.set("https://github.com/Corpauration/quarkus-annotations/issues")
                }
            }

        }
    }
}