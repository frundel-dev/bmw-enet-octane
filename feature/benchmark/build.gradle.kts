plugins { id("org.jetbrains.kotlin.jvm") }

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:diagnostics"))
    testImplementation("junit:junit:4.13.2")
}
