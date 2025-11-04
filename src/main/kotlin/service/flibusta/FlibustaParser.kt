package io.github.ryamal4.service.flibusta

import io.github.ryamal4.model.BookSequence
import io.github.ryamal4.model.BookSummary
import io.github.ryamal4.model.FullBookInfo
import org.jsoup.Jsoup

object FlibustaParser {
    fun parseBookSearchResults(html: String): List<BookSummary> {
        val document = Jsoup.parse(html)
        val booksHeader = document.select("h3").find { it.text().contains("Найденные книги") }

        if (booksHeader == null) {
            return emptyList()
        }

        val booksList = booksHeader.nextElementSibling()
        if (booksList == null || booksList.tagName() != "ul") {
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

    fun parseSequences(html: String): List<BookSequence> {
        val document = Jsoup.parse(html)
        val sequencesHeader = document.select("h3").find { it.text().contains("Найденные серии") }

        if (sequencesHeader == null) {
            return emptyList()
        }

        val sequencesList = sequencesHeader.nextElementSibling()
        if (sequencesList == null || sequencesList.tagName() != "ul") {
            return emptyList()
        }

        return sequencesList.select("li").mapNotNull { li ->
            val link = li.select("a").firstOrNull() ?: return@mapNotNull null
            val href = link.attr("href")
            val sequenceId = href.substringAfter("/sequence/").toIntOrNull() ?: return@mapNotNull null

            val text = li.text()
            val booksCountMatch = Regex("""\((\d+) книг""").find(text)
            val booksCount = booksCountMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0

            val title = link.text()

            BookSequence(sequenceId = sequenceId, title = title, booksCount = booksCount)
        }
    }

    fun parseSequenceBooks(html: String): List<BookSummary> {
        val document = Jsoup.parse(html)
        val bookItems = document.select("input[type=checkbox][name^=bchk]")

        return bookItems.mapNotNull { checkbox ->
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

            if (bookLink == null) return@mapNotNull null

            val bookId = bookLink.attr("href").substringAfter("/b/").toIntOrNull() ?: return@mapNotNull null
            val bookTitle = bookLink.text()
            val author = authorLink?.text() ?: ""

            BookSummary(id = bookId, author = author, title = bookTitle)
        }
    }

    fun parseBookPage(html: String, id: Int): FullBookInfo {
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

    fun sanitizeFileName(fileName: String): String {
        return fileName.replace(Regex("[<>:\"/\\\\|?*]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(200)
    }
}
