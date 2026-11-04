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
        applicationId = "ru.karasevm.privatednstoggle"
        versionCode = 19
        versionName = "1.11.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        targetSdk = 37
        minSdk = 28
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

    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    compileOnly(libs.hidden.stub)

    implementation(libs.hiddenapibypass)

    // Room components
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    androidTestImplementation(libs.androidx.room.testing)

    // Lifecycle components
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)

    // Compose
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)

    implementation(libs.aboutlibraries.core)
    implementation(libs.aboutlibraries.compose.m3)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
}
