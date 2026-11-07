import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "io.github.matakobrynky.opencrown"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.matakobrynky.opencrown"
        minSdk = 26
        targetSdk = 35
        versionCode = 24
        versionName = "2.3.0"
    }

    // Release signing comes from keystore.properties in the project root (git-ignored, see README).
    // Without it, release builds are simply unsigned.
    signingConfigs {
        val ksFile = keystoreProps.getProperty("storeFile")
        if (ksFile != null) create("release") {
            storeFile = rootProject.file(ksFile)
            storePassword = keystoreProps.getProperty("storePassword")
            keyAlias = keystoreProps.getProperty("keyAlias")
            keyPassword = keystoreProps.getProperty("keyPassword")
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
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    testOptions { unitTests { isIncludeAndroidResources = true; all { System.getenv("ROBOLECTRIC_REPO")?.let { url -> it.systemProperty("robolectric.dependency.repo.url", url) }; it.systemProperty("robolectric.graphicsMode", "NATIVE"); it.maxHeapSize = "2g" } } }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2025.01.00")
    implementation(bom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.test.ext:junit:1.2.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
