import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import java.util.Properties

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.20"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "dev.felix"
version = "1.0.0"

// 本机相关配置放在 local.properties（已 gitignore）：
//   localIdePath=/path/to/IntelliJ IDEA
// 缺失时 verifyPlugin 会自动选择合适的 IDE 发行版。
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val localIdePath: String? = localProperties.getProperty("localIdePath")

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // 编译目标取最低支持版本 2026.1：产物在 2026.2+ 上同样可用。
        // 官方建议：要支持旧版本，应降低编译目标平台，而不是降低 JDK。
        intellijIdea("2026.1")
        testFramework(TestFrameworkType.Platform)
    }

    // 用 Gson 树模型解析远端 JSON：字段缺失/漂移时手写兜底，比强类型反序列化更耐操。
    // 单独放在 lib/ 下，不重打包进主 jar。
    implementation("com.google.code.gson:gson:2.13.2")

    testImplementation("junit:junit:4.13.2")
}

kotlin {
    // 2026.1 平台字节码为 class file v65（Java 21）
    jvmToolchain(21)
}

intellijPlatform {
    pluginConfiguration {
        id = "dev.felix.maven-search"
        name = "Maven Coordinate Finder"
        version = project.version.toString()

        description = """
            <p><b>Search Maven coordinates without leaving the IDE.</b></p>
            <ul>
                <li>Search by keyword, <code>groupId:artifactId</code>, or a fully-qualified class name.</li>
                <li>List every published version from the authoritative <code>maven-metadata.xml</code>.</li>
                <li>Copy a ready-to-paste Maven XML, Gradle Groovy, or Gradle Kotlin DSL snippet.</li>
                <li>Two Maven Central endpoints with automatic fallback.</li>
                <li>Class-name lookup filters out shaded or relocated copies instead of guessing.</li>
            </ul>
            <p>The plugin never modifies build files on its own.</p>
            <p>All requests go directly to Maven Central. The plugin collects no telemetry and
            stores nothing outside your IDE settings.</p>
        """.trimIndent()

        changeNotes = """
            <h3>1.0.0</h3>
            <ul>
                <li>Initial release.</li>
                <li>Search by keyword, Maven coordinates, or fully-qualified class name.</li>
                <li>Full version list from maven-metadata.xml.</li>
                <li>Copy as Maven XML, Gradle Groovy, Gradle Kotlin DSL, or plain coordinates.</li>
                <li>Class-name results are filtered for exact matches; shaded copies are hidden by default.</li>
            </ul>
        """.trimIndent()

        vendor {
            name = "felix"
        }

        ideaVersion {
            sinceBuild = "261"
            // 官方推荐：不设上限，自动兼容后续所有 IDE 版本
            untilBuild = provider { null }
        }
    }

    // 发布到 JetBrains Marketplace 时对插件包签名。
    // 凭证只从环境变量读取，绝不写进仓库。
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        // gradle.properties 里有一行空的 intellijPlatformPublishingToken=（构建要求），
        // 必须过滤掉空值，否则会遮蔽 PUBLISH_TOKEN 环境变量。
        token = providers.gradleProperty("intellijPlatformPublishingToken")
            .filter { it.isNotBlank() }
            .orElse(providers.environmentVariable("PUBLISH_TOKEN"))
        channels = listOf("default")
    }

    pluginVerification {
        ides {
            if (localIdePath != null) {
                // 本机配置：复用已安装的 IDE，避免下载上 GB 的发行包
                local(file(localIdePath))
            } else {
                recommended()
            }
        }
    }
}
