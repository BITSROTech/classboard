plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "co.kys.classboard.lobby"
    compileSdk = 36

    defaultConfig {
        minSdk = 29

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.6.10" }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    val bom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(bom); androidTestImplementation(bom)

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")           // WebSocket Client
    implementation("org.java-websocket:Java-WebSocket:1.5.6")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("com.google.zxing:core:3.5.1")

    // QR 아이콘 사용 시 필요
    implementation("androidx.compose.material:material-icons-extended")
    implementation(project(":engine:drawing"))
    implementation(project(":core:proto"))
    implementation(project(":core:net"))
    api("org.jetbrains.kotlinx:kotlinx-serialization-core:1.7.1")
    api("org.jetbrains.kotlinx:kotlinx-serialization-cbor:1.7.1")
    implementation("com.google.zxing:core:3.5.3")                 // QR 생성
    implementation("com.journeyapps:zxing-android-embedded:4.3.0") // QR 스캔
    implementation("androidx.activity:activity-compose:1.9.2")

}