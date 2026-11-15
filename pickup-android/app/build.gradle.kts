plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

fun buildString(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "kr.ddakta.pickup"
    compileSdk { version = release(37) }
    defaultConfig {
        applicationId = "kr.ddakta.pickup"
        minSdk = 28
        targetSdk = 34
        versionCode = 10
        versionName = "0.2.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "API_URL", buildString(providers.gradleProperty("ddaktaApiUrl").orElse("http://10.0.2.2:8787").get()))
        buildConfigField("String", "INVITE_ORIGIN", buildString(providers.gradleProperty("ddaktaInviteOrigin").orElse("http://127.0.0.1:8787").get()))
        buildConfigField("String", "GLEO_PACKAGE", buildString(providers.gradleProperty("ddaktaGleoPackage").orElse("ai.umos.ambientai").get()))
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures { compose = true; buildConfig = true }
}

dependencies {
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
    implementation(platform("androidx.compose:compose-bom:2026.02.01"))
    implementation("androidx.activity:activity-compose:1.8.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.core:core-ktx:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.1")
    implementation("androidx.webkit:webkit:1.18.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
}
