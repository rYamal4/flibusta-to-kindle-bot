package service.flibusta

import io.github.ryamal4.service.flibusta.FlibustaParser
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import loadResource

class FlibustaParserTest : FunSpec({

    test("parseSearchPage - happy path with search_page.html") {
        val html = loadResource("/service/flibusta/search_page.html")

        val searchResults = FlibustaParser.parseSearchPage(html)

        searchResults.books shouldBe TestFixtures.searchPageBooks
        searchResults.sequences shouldBe TestFixtures.searchPageSequences
    }

    test("parseSearchPage - large_search_page complete validation") {
        val html = loadResource("/service/flibusta/large_search_page.html")

        val searchResults = FlibustaParser.parseSearchPage(html)

        searchResults.books shouldBe TestFixtures.largeSearchBooks
        searchResults.sequences shouldBe TestFixtures.largeSearchSequences
    }

    test("parseSearchPage - large_search_page validates size and extremes") {
        val html = loadResource("/service/flibusta/large_search_page.html")

        val searchResults = FlibustaParser.parseSearchPage(html)

        searchResults.books shouldHaveSize 50
        searchResults.books.first() shouldBe TestFixtures.largeSearchBooks.first()
        searchResults.books.last() shouldBe TestFixtures.largeSearchBooks.last()
        searchResults.books.map { it.id }.distinct().size shouldBe 50

        searchResults.sequences shouldHaveSize 50
        searchResults.sequences.all { it.booksCount > 0 } shouldBe true
        searchResults.sequences.maxOf { it.booksCount } shouldBe 23
        searchResults.sequences.first() shouldBe TestFixtures.largeSearchSequences.first()
        searchResults.sequences.last() shouldBe TestFixtures.largeSearchSequences.last()
    }

    test("parseSearchPage - handles missing data gracefully") {
        val htmlNoData =
            "<html><body><h3>Найденные книги</h3><p>No list</p><h3>Найденные серии</h3><p>No list</p></body></html>"

        val searchResults = FlibustaParser.parseSearchPage(htmlNoData)

        searchResults.books.shouldBeEmpty()
        searchResults.sequences.shouldBeEmpty()
    }

    test("parsePaginationInfo - returns null for search_page.html without pagination") {
        val html = loadResource("/service/flibusta/search_page.html")

        val paginationInfo = FlibustaParser.parsePagesCount(html)

        paginationInfo shouldBe null
    }

    test("parsePaginationInfo - extracts totalPages from large_search_page.html") {
        val html = loadResource("/service/flibusta/large_search_page.html")

        val paginationInfo = FlibustaParser.parsePagesCount(html)

        paginationInfo shouldBe 14
    }

    test("parseSequenceBooks - happy path with sequences_page.html") {
        val html = loadResource("/service/flibusta/sequences_page.html")

        val books = FlibustaParser.parseSequenceBooks(html)

        books shouldBe TestFixtures.sequencePageBooks
    }

    test("parseSequenceBooks - handles missing author") {
        val html = """
            <html><body>
                <input type="checkbox" name="bchk123">
                <a href="/b/123">Book Title</a>
                <br>
            </body></html>
        """.trimIndent()

        val books = FlibustaParser.parseSequenceBooks(html)

        books shouldHaveSize 1
        books[0].id shouldBe 123
        books[0].title shouldBe "Book Title"
        books[0].author shouldBe ""
    }

    test("parseSequenceBooks - skips malformed entries without book link") {
        val html = """
            <html><body>
                <input type="checkbox" name="bchk123">
                <a href="/a/456">Author Only</a>
                <br>
            </body></html>
        """.trimIndent()

        val books = FlibustaParser.parseSequenceBooks(html)

        books.shouldBeEmpty()
    }

    test("parseBookPage - happy path with book_page.html") {
        val html = loadResource("/service/flibusta/book_page.html")

        val bookInfo = FlibustaParser.parseBookPage(html, 162355)

        bookInfo shouldBe TestFixtures.bookPageInfo
    }

    test("parseBookPage - handles missing optional fields") {
        val htmlNoAnnotation = """
            <html><body>
                <h1 class="title">Test Book (fb2)</h1>
                <div id="main">
                    <a href="/a/123">Test Author</a>
                </div>
                <span style=size>100 с.</span>
            </body></html>
        """.trimIndent()

        val htmlNoPages = """
            <html><body>
                <h1 class="title">Test Book</h1>
                <div id="main">
                    <a href="/a/123">Test Author</a>
                </div>
                <h2>Аннотация</h2>
                <p>Test annotation</p>
            </body></html>
        """.trimIndent()

        val infoNoAnnotation = FlibustaParser.parseBookPage(htmlNoAnnotation, 999)
        infoNoAnnotation.summary.id shouldBe 999
        infoNoAnnotation.summary.title shouldBe "Test Book"
        infoNoAnnotation.summary.author shouldBe "Test Author"
        infoNoAnnotation.annotation shouldBe ""
        infoNoAnnotation.pagesCount shouldBe 100

        val infoNoPages = FlibustaParser.parseBookPage(htmlNoPages, 999)
        infoNoPages.pagesCount shouldBe 0
        infoNoPages.annotation shouldBe "Test annotation"
    }

    test("parseBookPage - removes format suffix from title") {
        val html = """
            <html><body>
                <h1 class="title">Test Book (epub)</h1>
                <div id="main">
                    <a href="/a/123">Test Author</a>
                </div>
            </body></html>
        """.trimIndent()

        val bookInfo = FlibustaParser.parseBookPage(html, 999)

        bookInfo.summary.title shouldBe "Test Book"
    }

    test("parseBookPage - removes format suffix case-insensitively") {
        val htmlUppercase = """
            <html><body>
                <h1 class="title">Test Book (FB2)</h1>
                <div id="main">
                    <a href="/a/123">Test Author</a>
                </div>
            </body></html>
        """.trimIndent()

        val htmlMixedCase = """
            <html><body>
                <h1 class="title">Another Book (Epub)</h1>
                <div id="main">
                    <a href="/a/456">Another Author</a>
                </div>
            </body></html>
        """.trimIndent()

        val infoUppercase = FlibustaParser.parseBookPage(htmlUppercase, 123)
        infoUppercase.summary.title shouldBe "Test Book"

        val infoMixedCase = FlibustaParser.parseBookPage(htmlMixedCase, 456)
        infoMixedCase.summary.title shouldBe "Another Book"
    }
})
