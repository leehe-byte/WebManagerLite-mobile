// Top-level build file
plugins {
    id("com.android.application") version "8.1.0" apply false
    id("com.android.library") version "8.1.0" apply false
    id("org.jetbrains.kotlin.android") version "1.8.10" apply false // 增加 Kotlin 声明
}

tasks.register("clean", Delete::class) {
    delete(rootProject.buildDir)
}
