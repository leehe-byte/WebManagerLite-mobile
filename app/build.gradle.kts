plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.opengw.manager"
    compileSdk = 33

    defaultConfig {
        applicationId = "com.opengw.manager"
        minSdk = 24
        targetSdk = 33
        versionCode = 8
        versionName = "2.1"
        setProperty("archivesBaseName", "${rootProject.name}-${versionName}")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.9.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // 高性能 HTTP 客户端: OkHttp
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    
    // 协程支持
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.6.4")

    // Lifecycle KTX (lifecycleScope)
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.5.1")
}