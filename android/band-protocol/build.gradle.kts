plugins {
    id("com.android.library")
}

android {
    namespace = "ru.kardiomonitor.bandprotocol"
    compileSdk = 36
    useLibrary("android.test.runner")
    useLibrary("android.test.base")

    defaultConfig {
        minSdk = 26
        testApplicationId = "ru.kardiomonitor.bandprotocol.test"
        testInstrumentationRunner = "android.test.InstrumentationTestRunner"
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
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    testImplementation("junit:junit:4.13.2")
}