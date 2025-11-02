package io.github.ryamal4.model

data class SearchResults(
    val sequences: List<BookSequence>,
    val books: List<BookSummary>
)
