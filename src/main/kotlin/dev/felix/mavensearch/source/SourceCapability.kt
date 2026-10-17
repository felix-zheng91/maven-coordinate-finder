package dev.felix.mavensearch.source

/** 数据源能力声明。UI 据此禁用不支持的模式，而不是静默降级。 */
enum class SourceCapability {
    KEYWORD_SEARCH,
    CLASS_SEARCH,
    VERSION_LIST,
}
