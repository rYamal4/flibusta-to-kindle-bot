package io.github.ryamal4.service.flibusta

import io.github.ryamal4.model.SearchResults

data class CachedSearch(
    val query: String,
    val searchResults: SearchResults,
    val timestamp: Long = System.currentTimeMillis()
)