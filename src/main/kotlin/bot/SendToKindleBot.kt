package io.github.ryamal4.bot

import com.github.kotlintelegrambot.bot
import com.github.kotlintelegrambot.dispatch
import com.github.kotlintelegrambot.dispatcher.callbackQuery
import com.github.kotlintelegrambot.dispatcher.command
import com.github.kotlintelegrambot.dispatcher.text
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.InlineKeyboardMarkup
import com.github.kotlintelegrambot.entities.keyboard.InlineKeyboardButton
import io.github.ryamal4.config.BotConfiguration
import io.github.ryamal4.model.BookSequence
import io.github.ryamal4.model.BookSummary
import io.github.ryamal4.service.KindleService
import io.github.ryamal4.service.flibusta.FlibustaService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import java.util.*

class SendToKindleBot(
    private val config: BotConfiguration,
    private val flibustaService: FlibustaService,
    private val kindleService: KindleService,
    private val dispatcher: CoroutineDispatcher
) {
    private val pageSize = 5
    private val searchSessions = mutableMapOf<String, String>()
    private val log = KotlinLogging.logger { }

    private val bot = bot {
        token = config.telegramBotToken

        dispatch {
            command("start") {
                if (!isAuthorized(message.from?.id)) {
                    sendUnauthorizedMessage(message.chat.id)
                    return@command
                }

                val welcomeMessage = """
                    Привет! Это бот для отправки книг на Kindle.

                    Доступные команды:
                    /search <название> - поиск книг на Flibusta
                    /info <book_id> - информация о книге
                    /send <book_id> - скачать и отправить на Kindle
                """.trimIndent()

                bot.sendMessage(
                    chatId = ChatId.fromId(message.chat.id),
                    text = welcomeMessage
                )
            }

            command("search") {
                val userId = message.from?.id
                if (!isAuthorized(userId)) {
                    log.warn { "Unauthorized search attempt by user $userId" }
                    sendUnauthorizedMessage(message.chat.id)
                    return@command
                }

                val query = args.joinToString(" ")
                if (query.isEmpty()) {
                    log.debug { "User $userId sent empty search query" }
                    bot.sendMessage(
                        chatId = ChatId.fromId(message.chat.id),
                        text = "Использование: /search <название книги>"
                    )
                    return@command
                }

                log.info { "User $userId searching for: '$query'" }

                withContext(dispatcher) {
                    try {
                        val searchResults = flibustaService.getBooks(query)

                        if (searchResults.books.isEmpty() && searchResults.sequences.isEmpty()) {
                            log.info { "Search '$query' returned no results" }
                            bot.sendMessage(
                                chatId = ChatId.fromId(message.chat.id),
                                text = "Ничего не найдено"
                            )
                            return@withContext
                        }
                        log.info { "Search '$query' found ${searchResults.books.size} books and ${searchResults.sequences.size} sequences" }

                        val page = 0
                        val totalItems = searchResults.sequences.size + searchResults.books.size
                        val totalPages = (totalItems + pageSize - 1) / pageSize

                        val sequencesOnPage = if (page == 0) searchResults.sequences else emptyList()
                        val booksStartIndex = maxOf(0, page * pageSize - searchResults.sequences.size)
                        val booksOnPage =
                            searchResults.books.drop(booksStartIndex).take(maxOf(0, pageSize - sequencesOnPage.size))

                        val messageText = formatBooksPage(
                            query,
                            page,
                            totalPages,
                            searchResults.books.size,
                            searchResults.sequences.size
                        )
                        val keyboard = createPaginationKeyboard(
                            getIdForQuery(query),
                            page,
                            totalPages,
                            booksOnPage,
                            sequencesOnPage
                        )

                        bot.sendMessage(
                            chatId = ChatId.fromId(message.chat.id),
                            text = messageText,
                            replyMarkup = keyboard
                        )
                    } catch (e: Exception) {
                        log.error(e) { "User $userId: Error searching for '$query'" }
                        bot.sendMessage(
                            chatId = ChatId.fromId(message.chat.id),
                            text = "Ошибка при поиске: ${e.message}"
                        )
                    }
                }
            }

            command("info") {
                val userId = message.from?.id
                if (!isAuthorized(userId)) {
                    log.warn { "Unauthorized info attempt by user $userId" }
                    sendUnauthorizedMessage(message.chat.id)
                    return@command
                }

                val bookId = args.firstOrNull()?.toIntOrNull()
                if (bookId == null) {
                    log.debug { "User $userId sent invalid book_id for info command" }
                    bot.sendMessage(
                        chatId = ChatId.fromId(message.chat.id),
                        text = "Использование: /info <book_id>"
                    )
                    return@command
                }

                log.info { "User $userId requesting info for book $bookId" }

                withContext(dispatcher) {
                    try {
                        val bookInfo = flibustaService.getBookInfo(bookId)
                        log.info { "User $userId: Retrieved info for book $bookId: '${bookInfo.summary.title}'" }

                        val message = """
                            ${bookInfo.summary.title}
                            Автор: ${bookInfo.summary.author}
                            ID: ${bookInfo.summary.id}
                            Страниц: ${bookInfo.pagesCount}

                            ${bookInfo.annotation}
                        """.trimIndent()

                        val keyboard = InlineKeyboardMarkup.create(
                            listOf(
                                InlineKeyboardButton.CallbackData(
                                    text = "На Kindle",
                                    callbackData = "send_${bookInfo.summary.id}"
                                )
                            )
                        )

                        bot.sendMessage(
                            chatId = ChatId.fromId(this@command.message.chat.id),
                            text = message,
                            replyMarkup = keyboard
                        )
                    } catch (e: Exception) {
                        log.error(e) { "User $userId: Error getting info for book $bookId" }
                        bot.sendMessage(
                            chatId = ChatId.fromId(this@command.message.chat.id),
                            text = "Ошибка получения информации: ${e.message}"
                        )
                    }
                }
            }

            command("send") {
                val userId = message.from?.id
                if (!isAuthorized(userId)) {
                    log.warn { "Unauthorized send attempt by user $userId" }
                    sendUnauthorizedMessage(message.chat.id)
                    return@command
                }

                val bookId = args.firstOrNull()?.toIntOrNull()
                if (bookId == null) {
                    log.debug { "User $userId sent invalid book_id for send command" }
                    bot.sendMessage(
                        chatId = ChatId.fromId(message.chat.id),
                        text = "Использование: /send <book_id>"
                    )
                    return@command
                }

                log.info { "User $userId: Starting send book $bookId to Kindle" }

                withContext(dispatcher) {
                    try {
                        bot.sendMessage(
                            chatId = ChatId.fromId(this@command.message.chat.id),
                            text = "Скачиваю книгу..."
                        )

                        val bookPath = flibustaService.downloadBook(bookId)
                        log.info { "User $userId: Downloaded book $bookId to ${bookPath}" }

                        bot.sendMessage(
                            chatId = ChatId.fromId(this@command.message.chat.id),
                            text = "Отправляю на Kindle..."
                        )

                        kindleService.sendToKindle(bookPath)
                        log.info { "User $userId: Successfully sent book $bookId to Kindle" }

                        bot.sendMessage(
                            chatId = ChatId.fromId(this@command.message.chat.id),
                            text = "Книга отправлена на Kindle!"
                        )
                    } catch (e: Exception) {
                        log.error(e) { "User $userId: Error sending book $bookId to Kindle" }
                        bot.sendMessage(
                            chatId = ChatId.fromId(this@command.message.chat.id),
                            text = "Ошибка отправки: ${e.message}"
                        )
                    }
                }
            }

            text {
                if (!isAuthorized(message.from?.id)) {
                    sendUnauthorizedMessage(message.chat.id)
                    return@text
                }

                val query = text.trim()
                if (query.isEmpty()) return@text

                withContext(dispatcher) {
                    try {
                        val searchResults = flibustaService.getBooks(query)

                        if (searchResults.books.isEmpty() && searchResults.sequences.isEmpty()) {
                            bot.sendMessage(
                                chatId = ChatId.fromId(message.chat.id),
                                text = "Ничего не найдено"
                            )
                            return@withContext
                        }

                        val page = 0
                        val totalItems = searchResults.sequences.size + searchResults.books.size
                        val totalPages = (totalItems + pageSize - 1) / pageSize

                        val sequencesOnPage = if (page == 0) searchResults.sequences else emptyList()
                        val booksStartIndex = maxOf(0, page * pageSize - searchResults.sequences.size)
                        val booksOnPage =
                            searchResults.books.drop(booksStartIndex).take(maxOf(0, pageSize - sequencesOnPage.size))

                        val messageText = formatBooksPage(
                            query,
                            page,
                            totalPages,
                            searchResults.books.size,
                            searchResults.sequences.size
                        )
                        val keyboard = createPaginationKeyboard(
                            getIdForQuery(query),
                            page,
                            totalPages,
                            booksOnPage,
                            sequencesOnPage
                        )

                        bot.sendMessage(
                            chatId = ChatId.fromId(message.chat.id),
                            text = messageText,
                            replyMarkup = keyboard
                        )
                    } catch (e: Exception) {
                        log.error(e) { "Error searching for books" }
                        bot.sendMessage(
                            chatId = ChatId.fromId(message.chat.id),
                            text = "Ошибка при поиске: ${e.message}"
                        )
                    }
                }
            }

            callbackQuery {
                val userId = callbackQuery.from.id
                if (!isAuthorized(userId)) {
                    log.warn { "Unauthorized callback query attempt by user $userId" }
                    callbackQuery.message?.chat?.id?.let { sendUnauthorizedMessage(it) }
                    return@callbackQuery
                }

                val data = callbackQuery.data
                log.debug { "User $userId: Callback query '$data'" }
                when {
                    data.startsWith("book_") -> {
                        val parts = data.removePrefix("book_").split("_")
                        if (parts.size == 3) {
                            val sessionId = parts[0]
                            val searchQuery = searchSessions[sessionId]
                            val page = parts[1].toIntOrNull()
                            val index = parts[2].toIntOrNull()

                            if (searchQuery == null || page == null || index == null) {
                                log.warn { "User $userId: Invalid book callback data - sessionId=$sessionId, page=$page, index=$index, query=$searchQuery" }
                                return@callbackQuery
                            }

                            val (books, sequences) = withContext(dispatcher) {
                                if (searchQuery.startsWith("seq:")) {
                                    val sequenceId = searchQuery.substringAfter("seq:").toIntOrNull()
                                    if (sequenceId != null) {
                                        flibustaService.getSequenceBooks(sequenceId) to emptyList()
                                    } else {
                                        emptyList<BookSummary>() to emptyList()
                                    }
                                } else {
                                    val results = flibustaService.getBooks(searchQuery)
                                    results.books to results.sequences
                                }
                            }

                            val booksStartIndex = maxOf(0, page * pageSize - sequences.size)
                            val bookIndexInResults = booksStartIndex + index
                            if (bookIndexInResults >= books.size) {
                                log.warn { "User $userId: Invalid book index $bookIndexInResults (books.size=${books.size})" }
                                return@callbackQuery
                            }

                            val book = books[bookIndexInResults]
                            log.info { "User $userId: Selected book ${book.id} '${book.title}'" }

                            withContext(dispatcher) {
                                try {
                                    val bookInfo = flibustaService.getBookInfo(book.id)
                                    val messageText = """
                                        ${bookInfo.summary.title}
                                        Автор: ${bookInfo.summary.author}
                                        ID: ${bookInfo.summary.id}
                                        Страниц: ${bookInfo.pagesCount}

                                        ${bookInfo.annotation}
                                    """.trimIndent()

                                    val keyboard = InlineKeyboardMarkup.create(
                                        listOf(
                                            InlineKeyboardButton.CallbackData(
                                                text = "На Kindle",
                                                callbackData = "send_${bookInfo.summary.id}"
                                            )
                                        )
                                    )

                                    bot.sendMessage(
                                        chatId = ChatId.fromId(callbackQuery.message?.chat?.id ?: return@withContext),
                                        text = messageText,
                                        replyMarkup = keyboard
                                    )
                                } catch (e: Exception) {
                                    log.error(e) { "Error getting book info" }
                                }
                            }
                        }
                    }

                    data.startsWith("page_") -> {
                        val parts = data.removePrefix("page_").split("_")
                        if (parts.size == 2) {
                            val sessionId = parts[0]
                            val searchQuery = searchSessions[sessionId]
                            val page = parts[1].toIntOrNull()

                            if (searchQuery == null || page == null) {
                                log.warn { "User $userId: Invalid page callback data - sessionId=$sessionId, page=$page, query=$searchQuery" }
                                return@callbackQuery
                            }

                            val (books, sequences) = withContext(dispatcher) {
                                if (searchQuery.startsWith("seq:")) {
                                    val sequenceId = searchQuery.substringAfter("seq:").toIntOrNull()
                                    if (sequenceId != null) {
                                        flibustaService.getSequenceBooks(sequenceId) to emptyList()
                                    } else {
                                        emptyList<BookSummary>() to emptyList()
                                    }
                                } else {
                                    val results = flibustaService.getBooks(searchQuery)
                                    results.books to results.sequences
                                }
                            }

                            val totalItems = sequences.size + books.size
                            val totalPages = (totalItems + pageSize - 1) / pageSize
                            if (page !in 0..<totalPages) {
                                log.warn { "User $userId: Invalid page number $page (totalPages=$totalPages)" }
                                return@callbackQuery
                            }

                            log.debug { "User $userId: Navigating to page $page for query '$searchQuery'" }

                            val sequencesOnPage = if (page == 0) sequences else emptyList()
                            val booksStartIndex = maxOf(0, page * pageSize - sequences.size)
                            val booksOnPage =
                                books.drop(booksStartIndex).take(maxOf(0, pageSize - sequencesOnPage.size))

                            val messageText = if (searchQuery.startsWith("seq:")) {
                                """
                                    Книги серии
                                    Найдено книг: ${books.size}
                                    Страница ${page + 1} из $totalPages
                                """.trimIndent()
                            } else {
                                formatBooksPage(searchQuery, page, totalPages, books.size, sequences.size)
                            }
                            val keyboard =
                                createPaginationKeyboard(sessionId, page, totalPages, booksOnPage, sequencesOnPage)

                            val chatId = callbackQuery.message?.chat?.id ?: return@callbackQuery
                            val messageId = callbackQuery.message?.messageId ?: return@callbackQuery

                            bot.deleteMessage(ChatId.fromId(chatId), messageId)
                            bot.sendMessage(
                                chatId = ChatId.fromId(chatId),
                                text = messageText,
                                replyMarkup = keyboard
                            )
                            bot.answerCallbackQuery(callbackQuery.id)
                        }
                    }

                    data.startsWith("sequence_") -> {
                        val sequenceId = data.substringAfter("sequence_").toIntOrNull()
                        if (sequenceId != null) {
                            withContext(dispatcher) {
                                try {
                                    val books = flibustaService.getSequenceBooks(sequenceId)

                                    if (books.isEmpty()) {
                                        bot.sendMessage(
                                            chatId = ChatId.fromId(
                                                callbackQuery.message?.chat?.id ?: return@withContext
                                            ),
                                            text = "Книги в серии не найдены"
                                        )
                                        return@withContext
                                    }

                                    val page = 0
                                    val totalPages = (books.size + pageSize - 1) / pageSize
                                    val booksOnPage = books.take(pageSize)

                                    val messageText = """
                                        Книги серии
                                        Найдено книг: ${books.size}
                                        Страница ${page + 1} из $totalPages
                                    """.trimIndent()

                                    val keyboard = createPaginationKeyboard(
                                        getIdForQuery("seq:$sequenceId"),
                                        page,
                                        totalPages,
                                        booksOnPage,
                                        emptyList()
                                    )

                                    bot.sendMessage(
                                        chatId = ChatId.fromId(callbackQuery.message?.chat?.id ?: return@withContext),
                                        text = messageText,
                                        replyMarkup = keyboard
                                    )
                                } catch (e: Exception) {
                                    log.error(e) { "Error getting sequence books" }
                                    bot.sendMessage(
                                        chatId = ChatId.fromId(callbackQuery.message?.chat?.id ?: return@withContext),
                                        text = "Ошибка при получении книг серии: ${e.message}"
                                    )
                                }
                            }
                        }
                    }

                    data == "noop" -> {
                        bot.answerCallbackQuery(callbackQuery.id)
                    }

                    data.startsWith("info_") -> {
                        val bookId = data.substringAfter("info_").toIntOrNull()
                        if (bookId != null) {
                            withContext(dispatcher) {
                                try {
                                    val bookInfo = flibustaService.getBookInfo(bookId)
                                    val messageText = """
                                        ${bookInfo.summary.title}
                                        Автор: ${bookInfo.summary.author}
                                        ID: ${bookInfo.summary.id}
                                        Страниц: ${bookInfo.pagesCount}

                                        ${bookInfo.annotation}
                                    """.trimIndent()

                                    val keyboard = InlineKeyboardMarkup.create(
                                        listOf(
                                            InlineKeyboardButton.CallbackData(
                                                text = "На Kindle",
                                                callbackData = "send_${bookInfo.summary.id}"
                                            )
                                        )
                                    )

                                    bot.sendMessage(
                                        chatId = ChatId.fromId(callbackQuery.message?.chat?.id ?: return@withContext),
                                        text = messageText,
                                        replyMarkup = keyboard
                                    )
                                } catch (e: Exception) {
                                    log.error(e) { "Error getting book info" }
                                }
                            }
                        }
                    }

                    data.startsWith("send_") -> {
                        val bookId = data.substringAfter("send_").toIntOrNull()
                        if (bookId != null) {
                            withContext(dispatcher) {
                                try {
                                    bot.sendMessage(
                                        chatId = ChatId.fromId(callbackQuery.message?.chat?.id ?: return@withContext),
                                        text = "Скачиваю книгу..."
                                    )

                                    val bookPath = flibustaService.downloadBook(bookId)

                                    bot.sendMessage(
                                        chatId = ChatId.fromId(callbackQuery.message?.chat?.id ?: return@withContext),
                                        text = "Отправляю на Kindle..."
                                    )

                                    kindleService.sendToKindle(bookPath)

                                    bot.sendMessage(
                                        chatId = ChatId.fromId(callbackQuery.message?.chat?.id ?: return@withContext),
                                        text = "Книга отправлена на Kindle!"
                                    )
                                } catch (e: Exception) {
                                    log.error(e) { "Error sending book to Kindle" }
                                    bot.sendMessage(
                                        chatId = ChatId.fromId(callbackQuery.message?.chat?.id ?: return@withContext),
                                        text = "Ошибка отправки: ${e.message}"
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun getIdForQuery(query: String): String {
        val sessionId = UUID.randomUUID().toString()
        searchSessions[sessionId] = query
        log.debug { "Created search session $sessionId for query: '$query'" }
        return sessionId
    }

    private fun formatBooksPage(
        query: String,
        page: Int,
        totalPages: Int,
        totalBooks: Int,
        totalSequences: Int = 0
    ): String {
        return if (totalSequences > 0) {
            """
            Результаты поиска: "$query"

            Найдено серий: $totalSequences
            Найдено книг: $totalBooks
            Страница ${page + 1} из $totalPages
            """.trimIndent()
        } else {
            """
            Результаты поиска: "$query"
            Найдено книг: $totalBooks
            Страница ${page + 1} из $totalPages
            """.trimIndent()
        }
    }

    private fun createPaginationKeyboard(
        sessionId: String,
        page: Int,
        totalPages: Int,
        books: List<BookSummary>,
        sequences: List<BookSequence> = emptyList()
    ): InlineKeyboardMarkup {
        val sequenceButtons = sequences.mapIndexed { _, sequence ->
            listOf(
                InlineKeyboardButton.CallbackData(
                    text = "${sequence.title} (${sequence.booksCount} книг)",
                    callbackData = "sequence_${sequence.sequenceId}"
                )
            )
        }

        val bookButtons = books.mapIndexed { index, book ->
            listOf(
                InlineKeyboardButton.CallbackData(
                    text = book.title,
                    callbackData = "book_${sessionId}_${page}_$index"
                )
            )
        }

        val navigationButtons = mutableListOf<InlineKeyboardButton>()

        if (page > 0) {
            navigationButtons.add(
                InlineKeyboardButton.CallbackData(
                    text = "⬅️ Назад",
                    callbackData = "page_${sessionId}_${page - 1}"
                )
            )
        }

        navigationButtons.add(
            InlineKeyboardButton.CallbackData(
                text = "${page + 1}/$totalPages",
                callbackData = "noop"
            )
        )

        if (page < totalPages - 1) {
            navigationButtons.add(
                InlineKeyboardButton.CallbackData(
                    text = "Вперед ➡️",
                    callbackData = "page_${sessionId}_${page + 1}"
                )
            )
        }

        return InlineKeyboardMarkup.create(
            sequenceButtons + bookButtons + listOf(navigationButtons)
        )
    }

    private fun isAuthorized(userId: Long?): Boolean {
        return userId == config.telegramUserId
    }

    private fun sendUnauthorizedMessage(chatId: Long) {
        bot.sendMessage(
            chatId = ChatId.fromId(chatId),
            text = """
                Этот бот доступен только для владельца.

                Вы можете развернуть свою копию бота:
                https://github.com/rYamal4/flibusta-to-kindle-bot
            """.trimIndent()
        )
    }

    fun start() {
        log.info { "Starting Telegram bot..." }
        bot.startPolling()
    }

    fun stop() {
        log.info { "Stopping Telegram bot..." }
        bot.stopPolling()
    }
}
