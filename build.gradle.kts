import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import java.util.Properties

// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.lsplugin.lsparanoid) apply false
}

extra["jdkVersion"] = 21

val workspaceKeystore = providers.environmentVariable("LT_BUILD_KEYSTORE").orNull
if (workspaceKeystore != null) {
    val password = providers.environmentVariable("LT_BUILD_KEY_PASSWORD").orNull
        ?: throw GradleException("Set LT_BUILD_KEY_PASSWORD or run the workspace release script.")
    extra["storeFile"] = workspaceKeystore
    extra["storePassword"] = password
    extra["keyAlias"] = "androiddebugkey"
    extra["keyPassword"] = password
} else {
    val keystorePropertiesFile = file("keystore/keystore.properties")
    if (!keystorePropertiesFile.isFile) {
        throw GradleException("Configure keystore/keystore.properties or run the workspace release script.")
    }
    val keystoreProperties = Properties().apply { keystorePropertiesFile.inputStream().use { load(it) } }
    for (key in listOf("storeFile", "storePassword", "keyAlias", "keyPassword")) {
        extra[key] = keystoreProperties.getProperty(key)
            ?: throw GradleException("Missing $key in keystore/keystore.properties.")
    }
}

tasks {
    register("clean", Delete::class) {
        delete(layout.buildDirectory)
        description = ""
    }
    withType<KotlinJvmCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }
}
