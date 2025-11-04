package io.github.ryamal4.service.flibusta

import io.github.ryamal4.model.SearchResults
import io.github.ryamal4.model.BookSequence
import io.github.ryamal4.model.BookSummary

data class Search(
    val query: String,
    val searchResults: SearchResults,
    val timestamp: Long = System.currentTimeMillis()
) {
    val sequences: List<BookSequence> get() = searchResults.sequences
    val books: List<BookSummary> get() = searchResults.books
}
