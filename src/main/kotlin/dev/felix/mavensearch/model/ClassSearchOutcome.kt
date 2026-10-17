package dev.felix.mavensearch.model

data class ClassSearchOutcome(
    val matches: List<ClassMatch>,
    /**
     * true 表示类名索引不可用，结果来自降级路径：
     * 按简单类名找候选，再逐个读取 jar 中央目录确认类存在。
     */
    val degraded: Boolean = false,
)
