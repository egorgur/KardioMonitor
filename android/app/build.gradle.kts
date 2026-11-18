plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val patientValidation = providers.gradleProperty("patientValidation").orNull == "true"
if (patientValidation) layout.buildDirectory.set(layout.projectDirectory.dir("build-validation"))

android {
    namespace = "ru.kardiomonitor"
    compileSdk = 36
    // Контекст из штатного instrumentation runner без новых AndroidX test-зависимостей.
    useLibrary("android.test.runner")
    useLibrary("android.test.base")

    defaultConfig {
        applicationId = if (patientValidation) "ru.kardiomonitor.patient.validation" else "ru.kardiomonitor"
        if (patientValidation) testApplicationId = "ru.kardiomonitor.patient.validation.test"
        manifestPlaceholders["applicationClass"] = if (patientValidation) "android.app.Application" else "ru.kardiomonitor.app.KardioApplication"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
        testInstrumentationRunner = "android.test.InstrumentationTestRunner"
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        // android.util.Log в сценариях: в JVM-тестах — заглушки, а не «Method not mocked»
        unitTests.isReturnDefaultValues = true
        unitTests.all {
            // Причина падения — в выводе Gradle, а не только в HTML-отчёте: её видят агент и Stop-hook
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
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("junit:junit:4.13.2")
    // org.json есть в Android, но в JVM-тестах — заглушки: настоящий — для тестов JSON (ADR-0006)
    testImplementation("org.json:json:20260814")
}
