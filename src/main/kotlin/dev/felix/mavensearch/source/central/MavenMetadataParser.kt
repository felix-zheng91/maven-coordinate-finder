package dev.felix.mavensearch.source.central

import dev.felix.mavensearch.model.ArtifactCoordinates
import dev.felix.mavensearch.model.ArtifactVersion
import dev.felix.mavensearch.model.ArtifactVersionList
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 解析 repo1.maven.org 的 maven-metadata.xml。
 *
 * 用 metadata 而不是 Solr 的 core=gav 拿版本列表，因为：
 * 1. 一次请求就能拿全量版本，不用翻 16 页；
 * 2. 带 <release> 字段，能区分正式版与 SNAPSHOT；
 * 3. 实测比搜索 API 更快更稳。
 */
object MavenMetadataParser {

    fun parse(xml: String, coordinates: ArtifactCoordinates): ArtifactVersionList {
        val doc = hardenedFactory().newDocumentBuilder()
            .parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))

        val versioning = doc.getElementsByTagName("versioning").item(0) as? Element
        val latest = versioning?.childText("latest")
        val release = versioning?.childText("release")

        // 注意：只在 <versioning> 下找 <version>。阿里云等镜像会在 <metadata> 根节点
        // 额外塞一个非标准 <version>，按全文档搜会混进来。
        val versions = versioning?.getElementsByTagName("version")?.let { nodes ->
            (0 until nodes.length).mapNotNull { index ->
                nodes.item(index).textContent?.trim()?.takeIf { it.isNotEmpty() }
            }
        }.orEmpty()

        return ArtifactVersionList(
            coordinates = coordinates,
            // metadata 里是发布顺序（旧 -> 新），界面约定新版本在前
            versions = versions.asReversed().map {
                ArtifactVersion(version = it, isLatest = it == latest, isRelease = it == release)
            },
            latest = latest,
            release = release,
        )
    }

    /** XML 来自网络，必须关掉外部实体，防 XXE。 */
    private fun hardenedFactory(): DocumentBuilderFactory =
        DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            isXIncludeAware = false
            isExpandEntityReferences = false
        }

    private fun Element.childText(tag: String): String? =
        getElementsByTagName(tag).item(0)?.textContent?.trim()?.takeIf { it.isNotEmpty() }
}
