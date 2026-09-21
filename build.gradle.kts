// 顶层构建文件。
//
// 注意：AGP 9 起内置 Kotlin 支持并默认启用，因此这里**不应**再声明
// org.jetbrains.kotlin.android 插件 —— 它与 AGP 9 的新 DSL 不兼容。
// Compose 编译器仍需要单独的插件（org.jetbrains.kotlin.plugin.compose）。
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose.compiler) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}
