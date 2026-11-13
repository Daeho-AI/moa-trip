plugins { id("com.android.application"); kotlin("android"); kotlin("plugin.serialization") }
android {
    namespace="dev.moa.trip"
    compileSdk=35
    useLibrary("android.car")
    defaultConfig {
        applicationId="dev.moa.trip"
        minSdk=33; targetSdk=35
        versionCode=3; versionName="0.4.1"
        testInstrumentationRunner="androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        create("submission") {
            val path=System.getenv("MOATRIP_STORE_FILE")
            if(!path.isNullOrBlank()) {
                storeFile=file(path)
                storePassword=System.getenv("MOATRIP_STORE_PASSWORD")
                keyAlias=System.getenv("MOATRIP_KEY_ALIAS") ?: "moatrip"
                keyPassword=System.getenv("MOATRIP_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        debug { isMinifyEnabled=false }
        release {
            isMinifyEnabled=false; isDebuggable=false
            if(!System.getenv("MOATRIP_STORE_FILE").isNullOrBlank()) signingConfig=signingConfigs.getByName("submission")
        }
    }
    compileOptions { sourceCompatibility=JavaVersion.VERSION_17; targetCompatibility=JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget="17" }
    buildFeatures { compose=true; buildConfig=true }
    composeOptions { kotlinCompilerExtensionVersion="1.5.10" }
    packaging { resources.excludes+="/META-INF/{AL2.0,LGPL2.1}" }
}
dependencies {
    implementation(project(":core"))
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.2")
    implementation(platform("androidx.compose:compose-bom:2024.04.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
    implementation("ai.pleos.playground:FusedLocation:1.1.3")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
