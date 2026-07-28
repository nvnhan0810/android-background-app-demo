plugins {
    id("com.android.application") version "8.7.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    // KSP = Kotlin Symbol Processing — biên dịch annotation (@Entity, @Dao của Room)
    id("com.google.devtools.ksp") version "2.0.21-1.0.28" apply false
}
