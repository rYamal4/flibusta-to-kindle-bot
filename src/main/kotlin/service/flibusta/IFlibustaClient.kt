package io.github.ryamal4.service.flibusta

import io.github.ryamal4.model.BookSummary
import io.github.ryamal4.model.FullBookInfo
import java.nio.file.Path

interface IFlibustaClient {
    suspend fun getBooks(title: String): List<BookSummary>

    suspend fun getBookInfo(id: Int): FullBookInfo

    suspend fun downloadBook(id: Int): Path
}