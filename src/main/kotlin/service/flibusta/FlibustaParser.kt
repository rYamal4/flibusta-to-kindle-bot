package io.github.ryamal4.service.flibusta

import io.github.ryamal4.model.BookSequence
import io.github.ryamal4.model.BookSummary
import io.github.ryamal4.model.FullBookInfo
import mu.KotlinLogging
import org.jsoup.Jsoup

private val log = KotlinLogging.logger {}

object FlibustaParser {
    fun parseBookSearchResults(html: String): List<BookSummary> {
        val document = Jsoup.parse(html)
        val booksHeader = document.select("h3").find { it.text().contains("Найденные книги") }

        if (booksHeader == null) {
            log.warn { "parseBookSearchResults: header 'Найденные книги' not found in HTML, returning empty list" }
            return emptyList()
        }

        val booksList = booksHeader.nextElementSibling()
        if (booksList == null || booksList.tagName() != "ul") {
            log.warn { "parseBookSearchResults: books <ul> not found after header (next sibling: ${booksList?.tagName() ?: "null"}), returning empty list" }
            return emptyList()
        }

        val totalLi = booksList.select("li").size
        val result = booksList.select("li").mapNotNull { li ->
            val links = li.select("a")
            if (links.size < 2) {
                log.debug { "parseBookSearchResults: skipped <li> with ${links.size} links (expected ≥2), text: '${li.text().take(50)}'" }
                return@mapNotNull null
            }

            val bookLink = links[0]
            val authorLink = links[1]

            val bookId = bookLink.attr("href").substringAfter("/b/").toIntOrNull()
            val bookTitle = bookLink.text()
            val author = authorLink.text()

            if (bookId != null) {
                BookSummary(id = bookId, author = author, title = bookTitle)
            } else {
                log.debug { "parseBookSearchResults: skipped book with invalid ID from href '${bookLink.attr("href")}', title: '$bookTitle'" }
                null
            }
        }

        log.debug { "parseBookSearchResults: parsed ${result.size} valid books from $totalLi total <li> elements" }
        return result
    }

    fun parseSequences(html: String): List<BookSequence> {
        val document = Jsoup.parse(html)
        val sequencesHeader = document.select("h3").find { it.text().contains("Найденные серии") }

        if (sequencesHeader == null) {
            log.warn { "parseSequences: header 'Найденные серии' not found in HTML, returning empty list" }
            return emptyList()
        }

        val sequencesList = sequencesHeader.nextElementSibling()
        if (sequencesList == null || sequencesList.tagName() != "ul") {
            log.warn { "parseSequences: sequences <ul> not found after header (next sibling: ${sequencesList?.tagName() ?: "null"}), returning empty list" }
            return emptyList()
        }

        val totalLi = sequencesList.select("li").size
        val result = sequencesList.select("li").mapNotNull { li ->
            val link = li.select("a").firstOrNull()
            if (link == null) {
                log.debug { "parseSequences: skipped <li> without <a> tag, text: '${li.text().take(50)}'" }
                return@mapNotNull null
            }

            val href = link.attr("href")
            val sequenceId = href.substringAfter("/sequence/").toIntOrNull()
            if (sequenceId == null) {
                log.debug { "parseSequences: skipped sequence with invalid ID from href '$href', title: '${link.text()}'" }
                return@mapNotNull null
            }

            val text = li.text()
            val booksCountMatch = Regex("""\((\d+) книг""").find(text)
            val booksCount = if (booksCountMatch != null) {
                booksCountMatch.groupValues[1].toIntOrNull() ?: 0
            } else {
                log.debug { "parseSequences: regex '\\(\\d+ книг' not matched in text '$text' for sequence $sequenceId, defaulting booksCount to 0" }
                0
            }

            val title = link.text()

            BookSequence(sequenceId = sequenceId, title = title, booksCount = booksCount)
        }

        log.debug { "parseSequences: parsed ${result.size} valid sequences from $totalLi total <li> elements (${totalLi - result.size} skipped)" }
        if (result.isNotEmpty()) {
            log.debug { "parseSequences: booksCount range: min=${result.minOf { it.booksCount }}, max=${result.maxOf { it.booksCount }}" }
        }
        return result
    }

    fun parseSequenceBooks(html: String): List<BookSummary> {
        val document = Jsoup.parse(html)
        val bookItems = document.select("input[type=checkbox][name^=bchk]")

        if (bookItems.isEmpty()) {
            log.warn { "parseSequenceBooks: no checkboxes found (selector 'input[type=checkbox][name^=bchk]'), returning empty list" }
            return emptyList()
        }

        val result = bookItems.mapNotNull { checkbox ->
            var sibling = checkbox.nextSibling()
            var bookLink: org.jsoup.nodes.Element? = null
            var authorLink: org.jsoup.nodes.Element? = null

            while (sibling != null) {
                if (sibling is org.jsoup.nodes.Element) {
                    if (sibling.tagName() == "br") {
                        break
                    }
                    if (sibling.tagName() == "a") {
                        val href = sibling.attr("href")
                        if (href.startsWith("/b/") && bookLink == null) {
                            bookLink = sibling
                        } else if (href.startsWith("/a/") && authorLink == null) {
                            authorLink = sibling
                        }
                    }
                }
                sibling = sibling.nextSibling()
            }

            if (bookLink == null) {
                log.debug { "parseSequenceBooks: skipped checkbox without book link (/b/), authorLink exists: ${authorLink != null}" }
                return@mapNotNull null
            }

            val bookId = bookLink.attr("href").substringAfter("/b/").toIntOrNull()
            if (bookId == null) {
                log.debug { "parseSequenceBooks: skipped book with invalid ID from href '${bookLink.attr("href")}', title: '${bookLink.text()}'" }
                return@mapNotNull null
            }

            val bookTitle = bookLink.text()
            val author = authorLink?.text() ?: ""

            if (authorLink == null) {
                log.debug { "parseSequenceBooks: book id=$bookId ('$bookTitle') has no author link, defaulting to empty string" }
            }

            BookSummary(id = bookId, author = author, title = bookTitle)
        }

        val invalidCount = bookItems.size - result.size
        val noAuthorCount = result.count { it.author.isEmpty() }
        log.debug { "parseSequenceBooks: parsed ${result.size} valid books from ${bookItems.size} checkboxes ($invalidCount skipped, $noAuthorCount without author)" }
        return result
    }

    fun parseBookPage(html: String, id: Int): FullBookInfo {
        val document = Jsoup.parse(html)

        val titleElement = document.select("h1.title").first()
        val rawTitle = titleElement?.text() ?: ""
        if (titleElement == null) {
            log.warn { "parseBookPage: <h1.title> not found for book id=$id, defaulting to empty title" }
        }

        val title = rawTitle.replace(Regex("""\s*\((fb2|epub|mobi|pdf|doc|txt|djvu|rtf)\)\s*$""", RegexOption.IGNORE_CASE), "")
        if (rawTitle != title) {
            log.debug { "parseBookPage: removed format suffix from title for book id=$id: '$rawTitle' → '$title'" }
        }

        val authorLink = document.select("#main a[href^=/a/]")
            .firstOrNull { link ->
                val text = link.text()
                !text.startsWith("[") && !text.endsWith("]") && link.attr("href") != "/a/all"
            }
        val author = authorLink?.text() ?: ""
        if (authorLink == null) {
            log.warn { "parseBookPage: author link not found for book id=$id, defaulting to empty author" }
        }

        val annotationHeader = document.select("h2").find { it.text().contains("Аннотация") }
        val annotation = if (annotationHeader != null) {
            val paragraphs = mutableListOf<String>()
            var element = annotationHeader.nextElementSibling()
            while (element != null && element.tagName() == "p") {
                paragraphs.add(element.text())
                element = element.nextElementSibling()
            }
            val result = paragraphs.joinToString("\n\n")
            log.debug { "parseBookPage: parsed annotation with ${paragraphs.size} paragraphs for book id=$id" }
            result
        } else {
            log.debug { "parseBookPage: annotation header not found for book id=$id, defaulting to empty annotation" }
            ""
        }

        val sizeSpan = document.select("span[style=size]").first()
        val pagesCount = if (sizeSpan != null) {
            val text = sizeSpan.text()
            val pagesMatch = Regex("""(\d+)\s*с\.""").find(text)
            if (pagesMatch == null) {
                log.debug { "parseBookPage: regex '(\\d+)\\s*с\\.' not matched in text '${sizeSpan.text()}' for book id=$id, defaulting pagesCount to 0" }
                0
            } else {
                pagesMatch.groupValues[1].toIntOrNull() ?: 0
            }
        } else {
            log.debug { "parseBookPage: <span style=size> not found for book id=$id, defaulting pagesCount to 0" }
            0
        }

        log.debug { "parseBookPage: parsed book id=$id - title: '$title', author: '$author', annotation: ${annotation.isNotEmpty()}, pages: $pagesCount" }

        val summary = BookSummary(id = id, author = author, title = title)
        return FullBookInfo(
            summary = summary,
            annotation = annotation,
            pagesCount = pagesCount
        )
    }

    fun sanitizeFileName(fileName: String): String {
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
}
