plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
    id("io.realm.kotlin")
}

android {
    namespace = "com.example.berryharvest"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.berryharvest"
        minSdk = 26  // Updated from 24 to 26 for Apache POI compatibility
        targetSdk = 34
        versionCode = 6
        versionName = "1.2.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        viewBinding = true
    }
    sourceSets {
        getByName("main") {
            assets.srcDirs("src/main/assets")
        }
    }
    buildToolsVersion = "35.0.0"
    ndkVersion = "25.1.8937393"
}

dependencies {
    // Excel generation dependencies - Apache POI (requires minSdk 26+)
    implementation("org.apache.poi:poi:5.2.3")
    implementation("org.apache.poi:poi-ooxml:5.2.3")
    implementation("org.apache.poi:poi-scratchpad:5.2.3")

    // Existing dependencies
    implementation("com.itextpdf:itextpdf:5.5.13.3")
    implementation("com.google.zxing:core:3.5.1")
    implementation("pub.devrel:easypermissions:3.0.0")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.0")
    implementation("io.realm.kotlin:library-base:1.11.0")
    implementation("io.realm.kotlin:library-sync:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.swiperefreshlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    // Realm
    // implementation(libs.realm.library.base)
}