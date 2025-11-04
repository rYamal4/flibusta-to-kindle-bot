package service.flibusta

import io.github.ryamal4.service.flibusta.FlibustaParser
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import loadResource

class FlibustaParserTest : FunSpec({

    test("parseBookSearchResults - happy path with search_page.html") {
        val html = loadResource("/service/flibusta/search_page.html")

        val books = FlibustaParser.parseBookSearchResults(html)

        books shouldHaveSize 4
        books[0].id shouldBe 475515
        books[0].title shouldBe "Ересь Хоруса: Омнибус. Том 3"
        books[0].author shouldBe "Дэн Абнетт"
    }

    test("parseBookSearchResults - empty when no books found") {
        val html = "<html><body><h3>Найденные книги</h3><p>No list</p></body></html>"

        val books = FlibustaParser.parseBookSearchResults(html)

        books.shouldBeEmpty()
    }

    test("parseSequences - happy path with search_page.html") {
        val html = loadResource("/service/flibusta/search_page.html")

        val sequences = FlibustaParser.parseSequences(html)

        sequences shouldHaveSize 7
        sequences[0].sequenceId shouldBe 23594
        sequences[0].title shouldBe "Ересь Хоруса"
        sequences[0].booksCount shouldBe 54

        sequences[6].sequenceId shouldBe 15523
        sequences[6].title shouldBe "Warhammer 40000: Ересь Хоруса"
        sequences[6].booksCount shouldBe 130
    }

    test("parseSequences - empty when no sequences found") {
        val html = "<html><body><h3>Найденные серии</h3><p>No list</p></body></html>"

        val sequences = FlibustaParser.parseSequences(html)

        sequences.shouldBeEmpty()
    }

    test("parseSequenceBooks - happy path with sequences_page.html") {
        val html = loadResource("/service/flibusta/sequences_page.html")

        val books = FlibustaParser.parseSequenceBooks(html)

        books shouldHaveSize 50
        books[0].id shouldBe 162355
        books[0].title shouldBe "Возвышение Хоруса"
        books[0].author shouldBe "Савельева, Ирина Викторовна"
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

    test("parseSequenceBooks - empty when no checkboxes found") {
        val html = "<html><body><p>No checkboxes here</p></body></html>"

        val books = FlibustaParser.parseSequenceBooks(html)

        books.shouldBeEmpty()
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

        bookInfo.summary.id shouldBe 162355
        bookInfo.summary.title shouldBe "Возвышение Хоруса"
        bookInfo.summary.author shouldBe "Дэн Абнетт"
        bookInfo.pagesCount shouldBe 347
        bookInfo.annotation shouldBe "То было легендарное время. Великий Крестовый Поход нес свет Имперских Истин в самые темные уголки Галактики, возвращая разрозненные миры человечества в лоно Империума и стирая чуждые расы с лица истории. По воле благословенного Императора, решившего отойти от ратных дел, бразды правления этой беспримерной кампанией были вручены Хорусу, примарху Легиона Лунных Волков.\n\nТак говорят летописи, но ни в одной из них не найти ответа на вопрос – когда, под небом какого мира проросли семена Великой Ереси. Может быть, это случилось в тот день, когда Хорус убил Императора в первый раз…"
    }

    test("parseBookPage - handles missing annotation") {
        val html = """
            <html><body>
                <h1 class="title">Test Book (fb2)</h1>
                <div id="main">
                    <a href="/a/123">Test Author</a>
                </div>
                <span style=size>100 с.</span>
            </body></html>
        """.trimIndent()

        val bookInfo = FlibustaParser.parseBookPage(html, 999)

        bookInfo.summary.id shouldBe 999
        bookInfo.summary.title shouldBe "Test Book"
        bookInfo.summary.author shouldBe "Test Author"
        bookInfo.annotation shouldBe ""
        bookInfo.pagesCount shouldBe 100
    }

    test("parseBookPage - handles missing pages count") {
        val html = """
            <html><body>
                <h1 class="title">Test Book</h1>
                <div id="main">
                    <a href="/a/123">Test Author</a>
                </div>
                <h2>Аннотация</h2>
                <p>Test annotation</p>
            </body></html>
        """.trimIndent()

        val bookInfo = FlibustaParser.parseBookPage(html, 999)

        bookInfo.pagesCount shouldBe 0
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

    test("sanitizeFileName - replaces special characters") {
        val result = FlibustaParser.sanitizeFileName("Book: Title <test>")

        result shouldBe "Book_ Title _test_"
    }

    test("sanitizeFileName - trims whitespace") {
        val result = FlibustaParser.sanitizeFileName("  Book Title  ")

        result shouldBe "Book Title"
    }

    test("sanitizeFileName - truncates to 200 characters") {
        val longName = "a".repeat(250)

        val result = FlibustaParser.sanitizeFileName(longName)

        result.length shouldBe 200
    }

    test("sanitizeFileName - collapses multiple spaces") {
        val result = FlibustaParser.sanitizeFileName("Book    Title    Test")

        result shouldBe "Book Title Test"
    }
})