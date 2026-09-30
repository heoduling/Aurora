plugins { java }
group = "gg.auroramc"
version = "1.0.0"
repositories { mavenCentral(); maven("https://repo.papermc.io/repository/maven-public/") }
dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.31-alpha")
    compileOnly(files(providers.gradleProperty("plugmanJar").get()))
}
java { toolchain.languageVersion.set(JavaLanguageVersion.of(25)) }
tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }
tasks.jar { manifest { attributes["paperweight-mappings-namespace"] = "mojang" } }
