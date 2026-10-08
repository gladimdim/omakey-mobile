import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Signing comes from the gitignored keystore.properties (see
// keystore.properties.example) or OMAKEY_STORE_* environment variables,
// never from this file. Without either, debug builds use the SDK's default
// debug key and release builds are left unsigned.
val signingProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun signingValue(key: String, env: String): String? = signingProps.getProperty(key) ?: System.getenv(env)
val storePath = signingValue("storeFile", "OMAKEY_STORE_FILE")

android {
    namespace = "com.gladimdim.omakey"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.gladimdim.omakey"
        minSdk = 29
        targetSdk = 35
        versionCode = 21
        versionName = "1.2.1"
    }

    signingConfigs {
        if (storePath != null) {
            create("omakey") {
                storeFile = file(storePath.replaceFirst("~", System.getProperty("user.home")))
                storePassword = signingValue("storePassword", "OMAKEY_STORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "OMAKEY_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "OMAKEY_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        val omakeySigning = signingConfigs.findByName("omakey")
        debug {
            if (omakeySigning != null) signingConfig = omakeySigning
        }
        release {
            isMinifyEnabled = false
            if (omakeySigning != null) signingConfig = omakeySigning
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":protocol"))
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    testImplementation("junit:junit:4.13.2")
    // org.json ships with Android but is stubbed in JVM unit tests.
    testImplementation("org.json:json:20240303")
}
