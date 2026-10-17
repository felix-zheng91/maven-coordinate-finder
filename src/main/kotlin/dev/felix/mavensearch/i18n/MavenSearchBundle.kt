package dev.felix.mavensearch.i18n

import com.intellij.DynamicBundle
import org.jetbrains.annotations.PropertyKey

private const val BUNDLE = "messages.MavenSearchBundle"

/** 跟随 IDE 语言（中文系统显示中文，其余显示英文）。 */
object MavenSearchBundle : DynamicBundle(BUNDLE) {
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String =
        getMessage(key, *params)
}
