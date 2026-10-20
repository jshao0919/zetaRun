import groovy.json.JsonSlurper

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
val firebaseFile = file("google-services.json")
var webClientId = ""
if (firebaseFile.exists()) {
    apply(plugin = "com.google.gms.google-services")
    val json = JsonSlurper().parse(firebaseFile) as Map<*, *>
    val clients = json["client"] as? List<*> ?: emptyList<Any>()
    val client = clients.mapNotNull { it as? Map<*, *> }.firstOrNull {
        val info = it["client_info"] as? Map<*, *>
        val android = info?.get("android_client_info") as? Map<*, *>
        android?.get("package_name") == "com.zetarun.app"
    }
    val oauth = client?.get("oauth_client") as? List<*> ?: emptyList<Any>()
    webClientId = oauth.mapNotNull { it as? Map<*, *> }
        .firstOrNull { (it["client_type"] as? Number)?.toInt() == 3 }
        ?.get("client_id") as? String ?: ""
    require(webClientId.isNotBlank()) { "Enable Google sign-in in Firebase and download google-services.json again; a Web OAuth client is required." }
}
android {
    namespace = "com.zetarun.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.zetarun.app"
        minSdk = 26
        targetSdk = 36
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("boolean", "FIREBASE_CONFIGURED", firebaseFile.exists().toString())
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"$webClientId\"")
    }
    buildFeatures { compose = true; buildConfig = true }
    buildToolsVersion = "36.0.0"
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildTypes {
        release { isMinifyEnabled = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro") }
    }
    androidResources { noCompress += listOf("mdl", "fst", "int", "txt", "conf") }
    packaging { jniLibs { useLegacyPackaging = false }; resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    testOptions { unitTests.isReturnDefaultValues = true }
}

val verifyVoiceModel by tasks.registering {
    doLast {
        check(file("src/main/assets/model-en-us/am/final.mdl").isFile) {
            "The offline English model is missing. Run ./scripts/prepare-model.sh from the repository root."
        }
    }
}
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach {
    dependsOn(verifyVoiceModel)
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")
    implementation("com.alphacephei:vosk-android:0.3.75@aar")
    implementation("net.java.dev.jna:jna:5.18.1@aar")
    implementation(platform("com.google.firebase:firebase-bom:33.12.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("androidx.credentials:credentials:1.5.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.5.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
