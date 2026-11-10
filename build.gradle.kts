plugins {
    id("com.android.application") version "9.5.1" apply false
    // AGP 9 kompiliert Kotlin selbst ("built-in Kotlin"), bringt aber nur Kotlin 2.2 mit.
    // GeckoView ist mit Kotlin 2.4 gebaut. Dieses Plugin wird NICHT angewendet (apply false) –
    // es legt nur die neuere Kotlin-Version in den Build-Klassenpfad, die AGP dann nutzt.
    id("org.jetbrains.kotlin.android") version "2.4.21" apply false
}
