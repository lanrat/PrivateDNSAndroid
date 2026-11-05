import com.android.build.api.dsl.ApplicationExtension

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
    alias(libs.plugins.aboutlibraries)
    alias(libs.plugins.aboutlibraries.android)
}

/**
 * The app version, from the nearest git tag named vMAJOR.MINOR.PATCH, so that a release only
 * needs a new tag. On the tagged commit it is e.g. "1.13.0"; on later commits e.g.
 * "1.13.0-4-gabc1234", with the same versionCode. versionCode is
 * MAJOR * 10000 + MINOR * 100 + PATCH, which grows with every release.
 * Without git or a matching tag (e.g. a shallow clone or a source archive) it is 0.0.0, which
 * the release workflow rejects.
 */
data class AppVersion(val name: String, val code: Int)

val appVersion: AppVersion = run {
    val describe = providers.exec {
        commandLine("git", "describe", "--tags", "--match", "v[0-9]*")
        isIgnoreExitValue = true
    }
    val output = runCatching {
        describe.standardOutput.asText.get().trim().takeIf { describe.result.get().exitValue == 0 }
    }.getOrNull()
    val match = output?.let { Regex("""v(\d+)\.(\d+)\.(\d+)(-\d+-g\p{XDigit}+)?""").matchEntire(it) }
    if (match == null) {
        logger.warn("No vMAJOR.MINOR.PATCH git tag found (git describe: $output), using version 0.0.0")
        AppVersion("0.0.0", 1)
    } else {
        val (major, minor, patch) = match.groupValues.subList(1, 4).map { it.toInt() }
        require(minor < 100 && patch < 100) { "Minor and patch versions must be below 100: $output" }
        AppVersion(output.removePrefix("v"), major * 10_000 + minor * 100 + patch)
    }
}

extensions.configure<ApplicationExtension>  {
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }
    androidResources {
        generateLocaleConfig = true
    }
    defaultConfig {
        // Differs from upstream (ru.karasevm.privatednstoggle) so both can be installed. The
        // namespace, and so the class names, stay the same to keep merging from upstream easy.
        applicationId = "com.vorsk.privatednstoggle"
        versionCode = appVersion.code
        versionName = appVersion.name

        targetSdk = 37
        minSdk = 28

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
        compose = true
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            manifestPlaceholders["appIcon"] = "@mipmap/ic_launcher"
        }
        debug {
            applicationIdSuffix = ".dev"
            manifestPlaceholders["appIcon"] = "@mipmap/ic_launcher_debug"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    dependenciesInfo {
        // Disables dependency metadata when building APKs.
        includeInApk = false
        // Disables dependency metadata when building Android App Bundles.
        includeInBundle = false
    }
    testOptions {
        // android.* calls (e.g. Log) return defaults in JVM unit tests instead of throwing
        unitTests.isReturnDefaultValues = true
    }
    lint {
        // Translations are managed on Weblate and are often incomplete; missing
        // strings fall back to English at runtime, so don't fail the build on them.
        disable += "MissingTranslation"
    }
    namespace = "ru.karasevm.privatednstoggle"
}

room {
    // Exported schemas are needed to write and test database migrations.
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.guava)
    implementation(libs.kotlinx.serialization.json)
    // Used directly; also keeps the instrumented tests on the same version (they run with the app's copy)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    compileOnly(libs.hidden.stub)

    implementation(libs.hiddenapibypass)

    // Room components
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Lifecycle components
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)

    implementation(libs.aboutlibraries.core)
    implementation(libs.aboutlibraries.compose.m3)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.sqlite.bundled.jvm)

    androidTestImplementation(libs.androidx.test.core.ktx)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
