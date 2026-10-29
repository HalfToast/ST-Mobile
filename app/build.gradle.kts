plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.halftoast.stmobile"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.halftoast.stmobile"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    // Release signing keys live in ~/.gradle/gradle.properties (never in the repo). Without them the
    // release build still configures and produces an unsigned APK.
    // A leading "~" means the user's home directory (Gradle doesn't expand it).
    val storeFile = providers.gradleProperty("STMOBILE_STORE_FILE").orNull?.let {
        if (it == "~" || it.startsWith("~/")) System.getProperty("user.home") + it.substring(1) else it
    }
    val storePassword = providers.gradleProperty("STMOBILE_STORE_PASSWORD").orNull
    val keyAliasProp = providers.gradleProperty("STMOBILE_KEY_ALIAS").orNull
    val keyPasswordProp = providers.gradleProperty("STMOBILE_KEY_PASSWORD").orNull
    val releaseSigningReady = listOf(storeFile, storePassword, keyAliasProp, keyPasswordProp).all { !it.isNullOrBlank() } &&
        file(storeFile!!).exists()

    signingConfigs {
        if (releaseSigningReady) {
            create("release") {
                this.storeFile = file(storeFile!!)
                this.storePassword = storePassword
                this.keyAlias = keyAliasProp
                this.keyPassword = keyPasswordProp
            }
        }
    }

    buildTypes {
        release {
            if (releaseSigningReady) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                logger.warn("WARNING: STMOBILE_* signing properties missing or keystore not found; the release APK will be unsigned.")
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.core)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.webkit)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.sshj)
    implementation(libs.bouncycastle)

    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
    testImplementation(libs.kotlinx.coroutines.test)
}
