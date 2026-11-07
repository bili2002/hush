plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sqldelight)
    application
}

kotlin {
    jvmToolchain(17)
}

// Server archive DB: SQLite via SQLDelight-jdbc, the same schema mechanism the phone uses.
sqldelight {
    databases {
        create("ArchiveDatabase") {
            packageName.set("com.hush.server.db")
        }
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.serialization.json)

    implementation(libs.sqldelight.jvm.driver)
    implementation(libs.logback.classic)

    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.content.negotiation)
}

application {
    mainClass.set("com.hush.server.ApplicationKt")
}

tasks.test {
    useJUnitPlatform()
    environment("HUSH_TOKEN", "test-token")
    environment("HUSH_DB_PATH", ":memory:")
}
