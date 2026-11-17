import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Default API URL baked into the app (editable in Settings): MOMENTUM_API_URL from the environment or the
// repo's git-ignored .env (written by scripts/write-env.sh). The API token is never baked in; it's typed
// into the app once (Settings → Sync).
val dotenv: Map<String, String> = rootProject.file("../.env").takeIf { it.exists() }
    ?.readLines()
    ?.filter { it.isNotBlank() && !it.trimStart().startsWith("#") && "=" in it }
    ?.associate { it.substringBefore("=").trim() to it.substringAfter("=").trim() }
    .orEmpty()
fun env(key: String): String = System.getenv(key) ?: dotenv[key] ?: ""

// Release signing: ~/.momentum/keystore.properties (outside the repo) with storeFile, storePassword, keyAlias.
val keystoreProps = Properties().apply {
    File(System.getProperty("user.home"), ".momentum/keystore.properties").takeIf { it.exists() }
        ?.inputStream()?.use { load(it) }
}
val appVersionCode: Int = (project.findProperty("versionCode") as String?)?.toInt() ?: 1

android {
    namespace = "com.thegalaxybyte.momentum"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.thegalaxybyte.momentum"
        minSdk = 28
        targetSdk = 36
        versionCode = appVersionCode
        versionName = "0.1.$appVersionCode"
        buildConfigField("String", "DEFAULT_API_URL", "\"${env("MOMENTUM_API_URL").trimEnd('/')}\"")
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storeType = "pkcs12"
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("storePassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    // Bundled data (food DB, exercise catalogue, demo images) lives in the repo's data/ folder.
    sourceSets["main"].assets.srcDirs("src/main/assets", "../../data")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.health.connect)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.compose)

    debugImplementation(libs.androidx.ui.tooling)
    testImplementation(libs.junit)
}
