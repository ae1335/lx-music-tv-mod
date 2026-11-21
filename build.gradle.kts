// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    id("com.android.application") version "9.3.0" apply false
    // 2.10 修复：AGP 9.x 内置 Kotlin 支持（Built-in Kotlin），
    // 再单独 apply org.jetbrains.kotlin.android 会导致 "extension 'kotlin' already registered"。
    // 因此这里只声明 compose / serialization / ksp 等额外插件；
    // Kotlin 本身由 AGP 内置，源码里的 kotlinOptions 改用 androidComponents 的内置 DSL。
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.10" apply false
    id("com.google.devtools.ksp") version "2.2.10-2.0.2" apply false
}
