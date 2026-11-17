plugins { id("com.android.test") }

android {
    namespace = "com.bendourthe.milestone.searchbenchmark"
    compileSdk = 37
    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true
    defaultConfig {
        minSdk = 29
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        create("benchmark") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.runner)
    implementation(libs.junit)
}
