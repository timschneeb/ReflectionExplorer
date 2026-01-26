plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.refine)
}

android {
    namespace = "me.timschneeberger.reflectionexplorer"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        minSdk = 28
    }

    buildFeatures {
        viewBinding = true
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
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.dexlib2)
    implementation(libs.ezxhelper.core)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    implementation(libs.refine.runtime)
    compileOnly(project(":library:hidden-api"))
}