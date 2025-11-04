fun loadResource(path: String): String {
    return object {}.javaClass.getResource(path)?.readText()
        ?: throw IllegalStateException("Resource not found: $path")
}