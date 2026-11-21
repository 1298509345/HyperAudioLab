plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "lab.hyperaudio"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    defaultConfig { applicationId = "lab.hyperaudio"; minSdk = 29; targetSdk = 35; versionCode = 12; versionName = "0.7.1"; testInstrumentationRunner = "lab.hyperaudio.ReceiverProbeInstrumentation" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies { testImplementation("junit:junit:4.13.2"); testImplementation("org.json:json:20240303") }
