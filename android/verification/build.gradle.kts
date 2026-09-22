plugins { kotlin("jvm") version "2.0.0" }
repositories { mavenCentral() }
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    testImplementation("junit:junit:4.13.2")
}
kotlin { jvmToolchain(17) }
sourceSets {
    main { kotlin.srcDir("../app/src/main/java"); kotlin.include("**/GlassesAudioGate.kt") }
    test { kotlin.srcDir("../app/src/test/java"); kotlin.include("**/GlassesAudioGateTest.kt") }
}
