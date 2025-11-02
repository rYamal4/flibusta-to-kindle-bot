package io.github.ryamal4.service.flibusta

import io.github.ryamal4.model.BookSummary

data class SearchResult(
    val query: String,
    val results: List<BookSummary>,
    val timestamp: Long = System.currentTimeMillis()
)
