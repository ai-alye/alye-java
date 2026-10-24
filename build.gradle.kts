plugins {
    `java-library`
    id("com.vanniktech.maven.publish") version "0.37.0"
}

group = "ai.alye"
version = "0.1.0"

repositories { mavenCentral() }

// Runs on Java 11+ (published Gradle metadata says 11, so Java 11-16 projects can use it).
java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

val openaiVersion = "4.91.0"
val anthropicVersion = "3.1.0"
val langchain4jVersion = "1.22.0"

dependencies {
    // Optional integrations: compiled against, never required at runtime.
    compileOnly("dev.langchain4j:langchain4j-core:$langchain4jVersion")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("com.openai:openai-java:$openaiVersion")
    testImplementation("com.anthropic:anthropic-java:$anthropicVersion")
    testImplementation("dev.langchain4j:langchain4j-core:$langchain4jVersion")
    testImplementation("dev.langchain4j:langchain4j-open-ai:$langchain4jVersion")
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(11)
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-processing", "-Xlint:-serial", "-Xlint:-try"))
}

tasks.processResources {
    val v = project.version.toString()
    inputs.property("version", v)
    filesMatching("ai/alye/version.properties") { expand("version" to v) }
}
tasks.test { useJUnitPlatform() }

tasks.jar {
    manifest {
        attributes(
            "Implementation-Title" to "alye",
            "Implementation-Version" to project.version,
            "Automatic-Module-Name" to "ai.alye",
        )
    }
}

mavenPublishing {
    publishToMavenCentral()
    // Signed only when publishing for real (needs signingInMemoryKey); local installs skip it.
    if (project.hasProperty("signingInMemoryKey")) signAllPublications()
    coordinates("ai.alye", "alye", project.version.toString())
    pom {
        name.set("Alye")
        description.set("Alye for Java: cost, latency and error tracking for every LLM call (OpenAI, Anthropic, LangChain4j).")
        url.set("https://www.alye.ai")
        inceptionYear.set("2026")
        licenses {
            license {
                name.set("Apache-2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        developers {
            developer {
                id.set("onruyl")
                name.set("Onruyl Private Limited")
                email.set("info@alye.ai")
                url.set("https://www.alye.ai")
            }
        }
        scm {
            url.set("https://www.alye.ai")
        }
    }
}
