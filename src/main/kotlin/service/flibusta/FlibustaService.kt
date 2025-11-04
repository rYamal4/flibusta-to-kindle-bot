package io.github.ryamal4.service.flibusta

import io.github.ryamal4.model.BookSummary
import io.github.ryamal4.model.FullBookInfo
import io.github.ryamal4.model.SearchResults
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.utils.io.jvm.javaio.*
import mu.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.div

class FlibustaService(val flibustaUrl: String) : IFlibustaService {
    private val log = KotlinLogging.logger { }
    private val searchCache = mutableMapOf<String, Search>()

    private companion object {
        const val ONE_HOUR_IN_MILLIS = 3600000
    }

    override suspend fun getBooks(query: String): SearchResults {
        getFromCache(query)?.let {
            log.info { "Cache hit for search '$query': ${it.books.size} books, ${it.sequences.size} sequences" }
            return it.searchResults
        }

        log.info { "Cache miss - searching for books with title: '$query'" }
        val client = HttpClient(CIO)

        return runCatching {
            val response = client.get("$flibustaUrl/booksearch") {
                parameter("ask", query)
            }

            val html = response.bodyAsText()
            val sequences = FlibustaParser.parseSequences(html)
            val books = FlibustaParser.parseBookSearchResults(html)

            log.info { "Search '$query' found ${books.size} books and ${sequences.size} sequences)" }

            searchCache[query] = Search(query, SearchResults(sequences, books))
            log.debug { "Cached search results for '$query' (cache size: ${searchCache.size})" }

            SearchResults(sequences, books)
        }.also {
            client.close()
        }.onFailure {
            log.error(it) { "Error while searching for '$query'" }
        }.getOrThrow()
    }

    override suspend fun getSequenceBooks(sequenceId: Int): List<BookSummary> {
        log.info { "Getting books for sequence id: $sequenceId" }
        val client = HttpClient(CIO)

        return runCatching {
            val response = client.get("$flibustaUrl/sequence/$sequenceId")
            val html = response.bodyAsText()
            val books = FlibustaParser.parseSequenceBooks(html)

            if (books.isEmpty()) {
                log.warn { "No books found in sequence $sequenceId" }
            }

            log.info { "Sequence $sequenceId contains ${books.size} books" }
            books
        }.also {
            client.close()
        }.onFailure {
            log.error(it) { "Error while getting books for sequence $sequenceId" }
        }.getOrThrow()
    }

    override suspend fun getBookInfo(bookId: Int): FullBookInfo {
        log.info { "Getting book info for id: $bookId" }
        val client = HttpClient(CIO)

        return runCatching {
            val response = client.get("$flibustaUrl/b/$bookId")
            val html = response.bodyAsText()

            val bookInfo = FlibustaParser.parseBookPage(html, bookId)

            log.info { "Retrieved info for book $bookId: '${bookInfo.summary.title}'" }
            bookInfo
        }.also {
            client.close()
        }.onFailure {
            log.error(it) { "Error while getting book info for book with id $bookId" }
        }.getOrThrow()
    }

    override suspend fun downloadBook(bookId: Int): Path {
        log.info { "Starting download for book $bookId" }
        val client = HttpClient(CIO)
        val url = "${flibustaUrl}/b/$bookId/epub"
        val startTime = System.currentTimeMillis()

        return this.runCatching {
            val response = client.get(url)
            val bookBytes = response.bodyAsChannel().toInputStream().readBytes()
            val downloadDuration = System.currentTimeMillis() - startTime
            val fileSizeKB = bookBytes.size / 1024

            log.info { "Downloaded book $bookId: ${fileSizeKB}KB in ${downloadDuration}ms" }

            val bookInfo = getBookInfo(bookId)
            val fileName =
                FlibustaParser.sanitizeFileName("${bookInfo.summary.title} - ${bookInfo.summary.author}.epub")

            val booksDir = Files.createTempDirectory("books")
            log.debug { "Created temp directory: $booksDir" }

            val bookFile = booksDir / fileName

            Files.write(bookFile, bookBytes)
            log.info { "Saved book $bookId to $bookFile" }

            bookFile
        }.also {
            client.close()
        }.onFailure {
            log.error(it) { "Error while downloading book with id $bookId" }
        }.getOrThrow()
    }

    private fun getFromCache(title: String): Search? {
        val searchResult = searchCache[title]
        val currentTime = System.currentTimeMillis()

        if (searchResult != null) {
            if (currentTime - searchResult.timestamp < ONE_HOUR_IN_MILLIS) {
                return searchResult
            } else {
                searchCache.remove(title)
                val ageMinutes = (currentTime - searchResult.timestamp) / 60000
                log.debug { "Removed expired cache entry for '$title' (age: ${ageMinutes} minutes, cache size: ${searchCache.size})" }
            }
        }

        return null
    }
}