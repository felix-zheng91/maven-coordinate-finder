package dev.felix.mavensearch

object Fixtures {
    fun load(name: String): String =
        Fixtures::class.java.getResourceAsStream("/fixtures/$name")
            ?.bufferedReader()
            ?.readText()
            ?: error("fixture not found: $name")
}
