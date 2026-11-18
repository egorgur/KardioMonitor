plugins {
    id("com.android.library")
}

android {
    namespace = "ru.kardiomonitor.architecture"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "android.test.InstrumentationTestRunner"
    }

    testOptions {
        unitTests.all {
            it.systemProperty("kardiomonitor.rootDir", rootProject.projectDir.absolutePath)
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
    testImplementation("junit:junit:4.13.2")
}
