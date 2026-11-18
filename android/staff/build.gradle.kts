plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val staffValidation = providers.gradleProperty("staffValidation").orNull == "true"
if (staffValidation) layout.buildDirectory.set(layout.projectDirectory.dir("build-validation"))

android {
    namespace = "ru.kardiomonitor.staff"
    compileSdk = 36

    defaultConfig {
        applicationId = if (staffValidation) "ru.kardiomonitor.staff.validation" else "ru.kardiomonitor.staff"
        if (staffValidation) testApplicationId = "ru.kardiomonitor.staff.validation.test"
        manifestPlaceholders["applicationClass"] = if (staffValidation) "android.app.Application" else "ru.kardiomonitor.staff.app.StaffApplication"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.all {
            it.testLogging {
                events("failed")
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":band-protocol"))

    val composeBom = platform("androidx.compose:compose-bom:2025.01.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("com.google.zxing:core:3.5.3")

    debugImplementation(composeBom)
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}