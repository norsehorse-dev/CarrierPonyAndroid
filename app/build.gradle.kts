import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing lives in keystore.properties (repo root), which stays OUT
// of the repo and out of any shared bundles. Debug builds never need it.
val keystoreProperties = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.carrierpony.app"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.carrierpony.app"
        minSdk = 24
        targetSdk = 36
        versionCode = 6
        versionName = "2.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            if (keystoreProperties.isNotEmpty()) {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystoreProperties.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    flavorDimensions += "distribution"
    productFlavors {
        // play -> Google Play. No SMS transport: SEND_SMS/RECEIVE_SMS are Play
        //         restricted permissions, so the Play listing stays clean.
        // foss -> F-Droid / IzzyOnDroid / direct site APK. Carries the SMS transport.
        create("play") {
            dimension = "distribution"
        }
        create("foss") {
            dimension = "distribution"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation("androidx.compose.material:material-icons-extended")
    implementation(libs.zxing.core)
    implementation(libs.zxing.embedded)
    implementation(libs.androidx.biometric)
    // biometric 1.1.0 drags in fragment 1.2.5, whose FragmentActivity still
    // enforces 16-bit requestCodes and predates the Activity Result registry;
    // modern androidx.activity launchers crash through it. Pin fragment up.
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.browser)
    // Firebase (FCM) is scoped to the play flavor only, so the FOSS variant
    // built by F-Droid carries no Google proprietary code on its classpath.
    "playImplementation"(platform(libs.firebase.bom))
    "playImplementation"(libs.firebase.messaging)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(project(":carrierponycore"))
    implementation("com.ponydirect:ponydirect:0.1.0")
    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

// Push stays dormant until the Firebase project exists: the google-services
// plugin only applies when app/google-services.json is present, so the build is
// green before and after Firebase setup. The json is gitignored and absent on
// the F-Droid FOSS build, so the plugin never applies there — the FOSS variant
// pulls in no Firebase dependency and no Google proprietary code. Drop the json
// in and re-sync to light FCM up on the play build.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}
