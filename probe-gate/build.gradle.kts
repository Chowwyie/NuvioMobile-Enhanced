plugins {
    kotlin("multiplatform") version "2.4.10"
}

kotlin {
    macosArm64 {
        binaries {
            executable {
                entryPoint = "probegate.main"
            }
        }
    }
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
                implementation("io.ktor:ktor-client-core:3.4.1")
            }
        }
        val macosMain by getting {
            dependencies {
                implementation("io.ktor:ktor-client-darwin:3.4.1")
            }
        }
    }
}

repositories {
    mavenCentral()
}
