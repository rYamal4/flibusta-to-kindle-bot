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
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.div

class FlibustaService(
    val flibustaUrl: String,
    private val client: HttpClient = HttpClient(CIO)
) : IFlibustaService, Closeable {
    private val log = KotlinLogging.logger { }
    private val searchCache = mutableMapOf<String, CachedSearch>()

    private companion object {
        const val ONE_HOUR_IN_MILLIS = 3600000
        const val MAX_PAGES_TO_LOAD = 5
    }

    override suspend fun searchBooks(query: String): SearchResults {
        getFromCache(query)?.let {
            log.info { "Cache hit for search '$query'" }
            return it.searchResults
        }

        log.info { "Cache miss - searching for books with title: '$query'" }

        return runCatching {
            val firstPageHtml = fetchSearchPage(query, 0)
            val pagesCount = FlibustaParser.parsePagesCount(firstPageHtml)
            val firstPageResults = FlibustaParser.parseSearchPage(firstPageHtml)

            val searchResults = if (pagesCount == null) {
                log.debug { "No pagination found for query '$query', parsing single page" }
                firstPageResults
            } else {
                val pagesToLoad = minOf(pagesCount, MAX_PAGES_TO_LOAD)

                log.info { "Found pagination for query '$query': total $pagesCount pages, loading first $pagesToLoad pages" }

                val allResults = mutableListOf(firstPageResults)

                for (page in 1..pagesToLoad) {
                    val pageHtml = fetchSearchPage(query, page)
                    allResults.add(FlibustaParser.parseSearchPage(pageHtml))
                }

                val mergedBooks = allResults.flatMap { it.books }
                val mergedSequences = allResults.flatMap { it.sequences }

                log.info { "Loaded ${pagesToLoad + 1} pages for query '$query': ${mergedSequences.size} sequences, ${mergedBooks.size} books total" }

                SearchResults(mergedSequences, mergedBooks)
            }
            log.info { "Search '$query' found ${searchResults.books.size} books and ${searchResults.sequences.size} sequences)" }

            searchCache[query] = CachedSearch(query, searchResults)
            log.debug { "Cached search results for '$query' (cache size: ${searchCache.size})" }

            searchResults
        }.onFailure {
            log.error(it) { "Error while searching for '$query'" }
        }.getOrThrow()
    }

    override suspend fun getSequenceBooks(sequenceId: Int): List<BookSummary> {
        log.info { "Getting books for sequence id: $sequenceId" }

        return runCatching {
            val response = client.get("$flibustaUrl/sequence/$sequenceId")
            val html = response.bodyAsText()
            val books = FlibustaParser.parseSequenceBooks(html)

            if (books.isEmpty()) {
                log.warn { "No books found in sequence $sequenceId" }
            }

            log.info { "Sequence $sequenceId contains ${books.size} books" }
            books
        }.onFailure {
            log.error(it) { "Error while getting books for sequence $sequenceId" }
        }.getOrThrow()
    }

    override suspend fun getBookInfo(bookId: Int): FullBookInfo {
        log.info { "Getting book info for id: $bookId" }

        return runCatching {
            val response = client.get("$flibustaUrl/b/$bookId")
            val html = response.bodyAsText()

            val bookInfo = FlibustaParser.parseBookPage(html, bookId)

            log.info { "Retrieved info for book $bookId: '${bookInfo.summary.title}'" }
            bookInfo
        }.onFailure {
            log.error(it) { "Error while getting book info for book with id $bookId" }
        }.getOrThrow()
    }

    override suspend fun downloadBook(bookId: Int): Path {
        log.info { "Starting download for book $bookId" }
        val url = "${flibustaUrl}/b/$bookId/epub"
        val startTime = System.currentTimeMillis()

        return runCatching {
            val response = client.get(url)
            val bookBytes = response.bodyAsChannel().toInputStream().readBytes()
            val downloadDuration = System.currentTimeMillis() - startTime
            val fileSizeKB = bookBytes.size / 1024

            log.info { "Downloaded book $bookId: ${fileSizeKB}KB in ${downloadDuration}ms" }

            val bookInfo = getBookInfo(bookId)
            val fileName = sanitizeFileName("${bookInfo.summary.title} - ${bookInfo.summary.author}.epub")

            val booksDir = Files.createTempDirectory("books")
            log.debug { "Created temp directory: $booksDir" }

            val bookFile = booksDir / fileName

            Files.write(bookFile, bookBytes)
            log.info { "Saved book $bookId to $bookFile" }

            bookFile
        }.onFailure {
            log.error(it) { "Error while downloading book with id $bookId" }
        }.getOrThrow()
    }

    private suspend fun fetchSearchPage(query: String, page: Int): String {
        val response = client.get("$flibustaUrl/booksearch") {
            parameter("ask", query)
            parameter("page", page)
            parameter("chs", "on")
            parameter("chb", "on")
        }
        return response.bodyAsText()
    }

    override fun close() {
        client.close()
    }

    private fun sanitizeFileName(fileName: String): String {
        val result = fileName.replace(Regex("[<>:\"/\\\\|?*]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(200)

        val wasModified = fileName != result
        val wasTruncated = fileName.length > 200

        if (wasModified || wasTruncated) {
            log.debug { "sanitizeFileName: modified filename (truncated: $wasTruncated, chars replaced: ${wasModified && !wasTruncated}): '$fileName' → '$result'" }
        }

        return result
    }

    private fun getFromCache(title: String): CachedSearch? {
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