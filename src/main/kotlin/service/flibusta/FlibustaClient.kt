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
import java.util.UUID
import kotlin.io.path.div

class FlibustaClient(val flibustaUrl: String) : IFlibustaClient {
    private val log = KotlinLogging.logger {  }
    private val searchSessions = mutableMapOf<String, SearchSession>()

    companion object {
        const val PAGE_SIZE = 5
    }

    override suspend fun getBooks(title: String): List<BookSummary> {
        val client = HttpClient(CIO)

        return runCatching {
            log.info { "Searching for books with title: $title" }
            val response = client.get("$flibustaUrl/booksearch") {
                parameter("ask", title)
            }
            val html = response.bodyAsText()

            parseBookSearchResults(html)
        }.also {
            client.close()
        }.getOrElse {
            log.error(it) { "Error while searching for books" }
            emptyList()
        }
    }

    override suspend fun getBookInfo(id: Int): FullBookInfo {
        val client = HttpClient(CIO)

        return runCatching {
            log.info { "Getting book info for id: $id" }
            val response = client.get("$flibustaUrl/b/$id")
            val html = response.bodyAsText()

            parseBookPage(html, id)
        }.also {
            client.close()
        }.getOrThrow()
    }

    override suspend fun downloadBook(id: Int): Path {
        val client = HttpClient(CIO)
        val url = "${flibustaUrl}/b/$id/epub"

        return this.runCatching {
            val response = client.get(url)
            val bookBytes = response.bodyAsChannel().toInputStream().readBytes()
            log.info { "Downloaded book with id $id" }

            // Получаем информацию о книге для формирования имени файла
            val bookInfo = getBookInfo(id)
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
        // Заменяем символы, недопустимые в именах файлов
        return fileName.replace(Regex("[<>:\"/\\\\|?*]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(200) // Ограничиваем длину имени файла
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

        // Ищем ссылку на автора, исключая навигационные ссылки в квадратных скобках
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

    fun createSearchSession(query: String, results: List<BookSummary>): String {
        cleanupOldSessions()
        val sessionId = UUID.randomUUID().toString().take(8)
        searchSessions[sessionId] = SearchSession(query, results)
        log.info { "Created search session $sessionId for query '$query' with ${results.size} results" }
        return sessionId
    }

    fun getSearchSession(sessionId: String): SearchSession? {
        return searchSessions[sessionId]
    }

    fun cleanupOldSessions() {
        val now = System.currentTimeMillis()
        val timeout = 3600_000L
        val before = searchSessions.size
        searchSessions.entries.removeIf { now - it.value.timestamp > timeout }
        val after = searchSessions.size
        if (before != after) {
            log.info { "Cleaned up ${before - after} old search sessions" }
        }
    }
}