plugins { java }
group = "gg.auroramc"
version = "1.0.0-2"
repositories { mavenCentral(); maven("https://repo.papermc.io/repository/maven-public/") }
dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.31-alpha")
    compileOnly(files(providers.gradleProperty("plugmanJar").get()))
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.1.0")
}
java { toolchain.languageVersion.set(JavaLanguageVersion.of(25)) }
tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }
tasks.jar { manifest { attributes["paperweight-mappings-namespace"] = "mojang" } }
tasks.test { useJUnitPlatform() }
