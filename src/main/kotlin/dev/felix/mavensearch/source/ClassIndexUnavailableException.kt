package dev.felix.mavensearch.source

/**
 * 类名索引不可用（legacy 端点下线、持续超时或被拦截）。
 * 调用方应切换到降级路径，而不是把它当成普通网络错误弹窗。
 */
class ClassIndexUnavailableException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
