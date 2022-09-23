plugins {
    kotlin("jvm") version "1.6.21"
    id("maven-publish")
}

group = "fr.corpauration"
version = "1.0.9"

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
    implementation("io.quarkus:quarkus-reactive-pg-client:2.12.3.Final")
    implementation("com.google.devtools.ksp:symbol-processing-api:1.6.21-1.0.6")
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions.jvmTarget = JavaVersion.VERSION_16.toString()
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