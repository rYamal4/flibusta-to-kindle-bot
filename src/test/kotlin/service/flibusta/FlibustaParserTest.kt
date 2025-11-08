package service.flibusta

import io.github.ryamal4.service.flibusta.FlibustaParser
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import loadResource

class FlibustaParserTest : FunSpec({

    test("parseBookSearchResults - happy path with search_page.html") {
        val html = loadResource("/service/flibusta/search_page.html")

        val books = FlibustaParser.parseBookSearchResults(html)

        books shouldBe TestFixtures.searchPageBooks
    }

    test("parseBookSearchResults - large_search_page complete validation") {
        val html = loadResource("/service/flibusta/large_search_page.html")

        val books = FlibustaParser.parseBookSearchResults(html)

        books shouldBe TestFixtures.largeSearchBooks
    }

    test("parseBookSearchResults - large_search_page validates size and extremes") {
        val html = loadResource("/service/flibusta/large_search_page.html")

        val books = FlibustaParser.parseBookSearchResults(html)

        books shouldHaveSize 50
        books.first() shouldBe TestFixtures.largeSearchBooks.first()
        books.last() shouldBe TestFixtures.largeSearchBooks.last()
        books.map { it.id }.distinct().size shouldBe 50
    }

    test("parseBookSearchResults - handles missing data gracefully") {
        val htmlNoBooks = "<html><body><h3>Найденные книги</h3><p>No list</p></body></html>"

        val books = FlibustaParser.parseBookSearchResults(htmlNoBooks)

        books.shouldBeEmpty()
    }

    test("parseSequences - happy path with search_page.html") {
        val html = loadResource("/service/flibusta/search_page.html")

        val sequences = FlibustaParser.parseSequences(html)

        sequences shouldBe TestFixtures.searchPageSequences
    }

    test("parseSequences - large_search_page complete validation") {
        val html = loadResource("/service/flibusta/large_search_page.html")

        val sequences = FlibustaParser.parseSequences(html)

        sequences shouldBe TestFixtures.largeSearchSequences
    }

    test("parseSequences - large_search_page validates book counts range") {
        val html = loadResource("/service/flibusta/large_search_page.html")

        val sequences = FlibustaParser.parseSequences(html)

        sequences shouldHaveSize 50
        sequences.all { it.booksCount > 0 } shouldBe true
        sequences.maxOf { it.booksCount } shouldBe 23
        sequences.first() shouldBe TestFixtures.largeSearchSequences.first()
        sequences.last() shouldBe TestFixtures.largeSearchSequences.last()
    }

    test("parseSequences - handles missing data gracefully") {
        val htmlNoSequences = "<html><body><h3>Найденные серии</h3><p>No list</p></body></html>"

        val sequences = FlibustaParser.parseSequences(htmlNoSequences)

        sequences.shouldBeEmpty()
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

    test("sanitizeFileName - normalizes correctly") {
        val input = "  Book:Title<>|?*(multiple   spaces).txt"

        val result = FlibustaParser.sanitizeFileName(input)

        result shouldBe "Book_Title_____(multiple spaces).txt"
        result shouldNotContain ":"
        result shouldNotContain "<"
        result shouldNotContain ">"
        result shouldNotContain "|"
        result shouldNotContain "?"
        result shouldNotContain "*"
    }

    test("sanitizeFileName - truncates to 200 characters") {
        val longName = "a".repeat(250)

        val result = FlibustaParser.sanitizeFileName(longName)

        result.length shouldBe 200
    }
})
