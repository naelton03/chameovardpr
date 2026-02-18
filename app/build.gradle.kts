import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.JavaVersion
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

apply(plugin = "com.android.application")
apply(plugin = "org.jetbrains.kotlin.android")

extensions.configure<ApplicationExtension>("android") {
    namespace = "com.example.replaycam"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.replaycam"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
    }
}

tasks.withType<KotlinCompile>().configureEach {
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    val cameraxVersion = "1.3.4"

    add("implementation", "androidx.core:core-ktx:1.13.1")
    add("implementation", "androidx.appcompat:appcompat:1.7.0")
    add("implementation", "com.google.android.material:material:1.12.0")
    add("implementation", "androidx.constraintlayout:constraintlayout:2.1.4")

    add("implementation", "androidx.camera:camera-core:$cameraxVersion")
    add("implementation", "androidx.camera:camera-camera2:$cameraxVersion")
    add("implementation", "androidx.camera:camera-lifecycle:$cameraxVersion")
    add("implementation", "androidx.camera:camera-video:$cameraxVersion")
    add("implementation", "androidx.camera:camera-view:$cameraxVersion")

    add("implementation", "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    add("implementation", "com.google.android.gms:play-services-auth:21.2.0")
    add("implementation", "com.google.api-client:google-api-client-android:2.6.0")
    add("implementation", "com.google.apis:google-api-services-youtube:v3-rev20240814-2.0.0")
}
