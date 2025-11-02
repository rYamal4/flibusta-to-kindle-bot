package io.github.ryamal4.service.flibusta

import io.github.ryamal4.model.BookSummary
import io.github.ryamal4.model.FullBookInfo
import io.github.ryamal4.model.SearchResults
import java.nio.file.Path

interface IFlibustaClient {
    suspend fun getBooks(title: String): SearchResults

    suspend fun getSequenceBooks(sequenceId: Int): List<BookSummary>

    suspend fun getBookInfo(bookId: Int): FullBookInfo

    suspend fun downloadBook(bookId: Int): Path
}