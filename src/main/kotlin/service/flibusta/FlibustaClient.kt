package io.github.ryamal4.service.flibusta

import io.github.ryamal4.model.BookSummary
import io.github.ryamal4.model.FullBookInfo
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.utils.io.jvm.javaio.toInputStream
import mu.KotlinLogging
import org.jsoup.Jsoup
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.div

class FlibustaClient(val flibustaUrl: String) : IFlibustaClient {
    private val oneHourInMillis = 3600000;
    private val log = KotlinLogging.logger {  }
    private val searchCache = mutableMapOf<String, SearchResult>()

    override suspend fun getBooks(title: String): List<BookSummary> {
        getFromCache(title)?.let {
            log.info { "returned search result from cache" }
            return it
        }

        val client = HttpClient(CIO)

        return runCatching {
            log.info { "Searching for books with title: $title" }
            val response = client.get("$flibustaUrl/booksearch") {
                parameter("ask", title)
            }
            val html = response.bodyAsText()

            val results = parseBookSearchResults(html)
            searchCache[title] = SearchResult(title, results)
            results
        }.also {
            client.close()
        }.getOrElse {
            log.error(it) { "Error while searching for books" }
            emptyList()
        }
    }

    override suspend fun getSequnceBooks(sequenceId: Int) {
        TODO("Not yet implemented")
    }

    override suspend fun getBookInfo(bookId: Int): FullBookInfo {
        val client = HttpClient(CIO)

        return runCatching {
            log.info { "Getting book info for id: $bookId" }
            val response = client.get("$flibustaUrl/b/$bookId")
            val html = response.bodyAsText()

            parseBookPage(html, bookId)
        }.also {
            client.close()
        }.getOrThrow()
    }

    override suspend fun downloadBook(bookId: Int): Path {
        val client = HttpClient(CIO)
        val url = "${flibustaUrl}/b/$bookId/epub"

        return this.runCatching {
            val response = client.get(url)
            val bookBytes = response.bodyAsChannel().toInputStream().readBytes()
            log.info { "Downloaded book with id $bookId" }

            val bookInfo = getBookInfo(bookId)
            val fileName = sanitizeFileName("${bookInfo.summary.title} - ${bookInfo.summary.author}.epub")

            val booksDir = Files.createTempDirectory("books")
            val bookFile = booksDir / fileName

            Files.write(bookFile, bookBytes)

            bookFile
        }.also {
            client.close()
        }.getOrThrow()
    }

    private fun sanitizeFileName(fileName: String): String {
        return fileName.replace(Regex("[<>:\"/\\\\|?*]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(200)
    }

    private fun parseBookSearchResults(html: String): List<BookSummary> {
        val document = Jsoup.parse(html)
        val booksHeader = document.select("h3").find { it.text().contains("Найденные книги") }

        if (booksHeader == null) {
            log.warn { "No books found in HTML" }
            return emptyList()
        }

        val booksList = booksHeader.nextElementSibling()
        if (booksList == null || booksList.tagName() != "ul") {
            log.warn { "Books list not found" }
            return emptyList()
        }

        return booksList.select("li").mapNotNull { li ->
            val links = li.select("a")
            if (links.size < 2) return@mapNotNull null

            val bookLink = links[0]
            val authorLink = links[1]

            val bookId = bookLink.attr("href").substringAfter("/b/").toIntOrNull()
            val bookTitle = bookLink.text()
            val author = authorLink.text()

            if (bookId != null) {
                BookSummary(id = bookId, author = author, title = bookTitle)
            } else {
                null
            }
        }
    }

    private fun parseBookPage(html: String, id: Int): FullBookInfo {
        val document = Jsoup.parse(html)

        val titleElement = document.select("h1.title").first()
        val rawTitle = titleElement?.text() ?: ""
        val title = rawTitle.replace(Regex("""\s*\((fb2|epub|mobi|pdf|doc|txt|djvu|rtf)\)\s*$"""), "")

        val authorLink = document.select("#main a[href^=/a/]")
            .firstOrNull { link ->
                val text = link.text()
                !text.startsWith("[") && !text.endsWith("]") && link.attr("href") != "/a/all"
            }
        val author = authorLink?.text() ?: ""

        val annotationHeader = document.select("h2").find { it.text().contains("Аннотация") }
        val annotation = if (annotationHeader != null) {
            val paragraphs = mutableListOf<String>()
            var element = annotationHeader.nextElementSibling()
            while (element != null && element.tagName() == "p") {
                paragraphs.add(element.text())
                element = element.nextElementSibling()
            }
            paragraphs.joinToString("\n\n")
        } else {
            ""
        }

        val sizeSpan = document.select("span[style=size]").first()
        val pagesCount = if (sizeSpan != null) {
            val text = sizeSpan.text()
            val pagesMatch = Regex("""(\d+)\s*с\.""").find(text)
            pagesMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
        } else {
            0
        }

        val summary = BookSummary(id = id, author = author, title = title)
        return FullBookInfo(
            summary = summary,
            annotation = annotation,
            pagesCount = pagesCount
        )
    }

    private fun getFromCache(title: String): List<BookSummary>? {
        val searchResult = searchCache[title]
        val currentTime = System.currentTimeMillis()

        if (searchResult != null) {
            if (currentTime - searchResult.timestamp < oneHourInMillis) {
                searchResult.results
            } else {
                searchCache.remove(title)
            }
        }

        return null
    }
}