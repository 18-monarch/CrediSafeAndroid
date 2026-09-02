import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use(::load)
}

val signingProperties = Properties().apply {
    val file = rootProject.file("signing.properties")
    if (file.exists()) file.inputStream().use(::load)
}

val configValue: (String, String) -> String = { name, fallback ->
    localProperties.getProperty(name)?.takeIf { it.isNotBlank() }
        ?: System.getenv(name)?.takeIf { it.isNotBlank() }
        ?: providers.gradleProperty(name).orNull?.takeIf { it.isNotBlank() }
        ?: fallback
}

val releaseStoreFile = signingProperties.getProperty("storeFile")?.takeIf { it.isNotBlank() }
val releaseStorePassword = signingProperties.getProperty("storePassword")?.takeIf { it.isNotBlank() }
val releaseKeyAlias = signingProperties.getProperty("keyAlias")?.takeIf { it.isNotBlank() }
val releaseKeyPassword = signingProperties.getProperty("keyPassword")?.takeIf { it.isNotBlank() }
val hasReleaseSigning =
    releaseStoreFile != null &&
    releaseStorePassword != null &&
    releaseKeyAlias != null &&
    releaseKeyPassword != null

android {
    namespace = "com.credisafe.mobile"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.credisafe.mobile"
        minSdk = 26
        targetSdk = 37
        versionCode = configValue("CREDISAFE_VERSION_CODE", "26").toIntOrNull() ?: 26
        versionName = configValue("CREDISAFE_VERSION_NAME", "2.7.0-beta.2")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-dev"
            val apiBaseUrl = configValue(
                "CREDISAFE_API_DEBUG_URL",
                "https://credisafeandroid.onrender.com/v1/",
            )
            buildConfigField("String", "CREDISAFE_API_BASE_URL", "\"${apiBaseUrl.trimEnd('/')}/\"")
            buildConfigField("String", "DISTRIBUTION_CHANNEL", "\"development\"")
            buildConfigField("String", "MAP_STYLE_URL", "\"https://tiles.openfreemap.org/styles/liberty\"")
        }
        release {
            isDebuggable = false
            isMinifyEnabled = false
            isShrinkResources = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            val apiBaseUrl = configValue(
                "CREDISAFE_API_RELEASE_URL",
                "https://credisafeandroid.onrender.com/v1/",
            )
            buildConfigField("String", "CREDISAFE_API_BASE_URL", "\"${apiBaseUrl.trimEnd('/')}/\"")
            buildConfigField("String", "DISTRIBUTION_CHANNEL", "\"website-beta\"")
            buildConfigField("String", "MAP_STYLE_URL", "\"https://tiles.openfreemap.org/styles/liberty\"")
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

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2026.08.00")
    implementation(bom)

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.fragment:fragment:1.8.9")
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("com.google.android.gms:play-services-location:21.4.0")
    implementation("org.maplibre.gl:android-sdk:13.4.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.work:work-runtime-ktx:2.11.2")
    implementation("androidx.security:security-crypto:1.1.0")

    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("com.jakewharton.retrofit:retrofit2-kotlinx-serialization-converter:1.0.0")

    testImplementation("junit:junit:4.13.2")
}
