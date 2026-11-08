plugins {
    // AGP 9 bringt die Kotlin-Unterstützung bereits mit (kein separates Kotlin-Plugin nötig)
    id("com.android.application") version "9.5.1" apply false
    // Neuere Kotlin-Version als die in AGP eingebaute – GeckoView braucht Kotlin 2.4
    id("org.jetbrains.kotlin.android") version "2.4.21" apply false
}
