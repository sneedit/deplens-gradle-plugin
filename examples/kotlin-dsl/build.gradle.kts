plugins {
    kotlin("jvm") version "2.2.0"
    id("com.sneedit.deplens") version "0.3.0"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.google.guava:guava:31.0-jre")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.0")
    
    testImplementation(kotlin("test"))
}

configure<com.sneedit.deplens.DepLensExtension> {
    rules {
        global { includePreReleases.set(false) }
        dependency("com.google.guava:guava") { pinned.set(true) }
        plugin("org.jetbrains.kotlin.jvm") { allowedUpdates.set("minor") }
    }
}

tasks.test {
    useJUnitPlatform()
}
