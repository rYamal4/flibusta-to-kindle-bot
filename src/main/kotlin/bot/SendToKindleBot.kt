package io.github.ryamal4.bot

import com.github.kotlintelegrambot.bot
import com.github.kotlintelegrambot.dispatch
import com.github.kotlintelegrambot.dispatcher.Dispatcher
import com.github.kotlintelegrambot.dispatcher.command
import com.github.kotlintelegrambot.dispatcher.callbackQuery
import com.github.kotlintelegrambot.dispatcher.text
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.InlineKeyboardMarkup
import com.github.kotlintelegrambot.entities.keyboard.InlineKeyboardButton
import io.github.ryamal4.config.BotConfiguration
import io.github.ryamal4.service.KindleService
import io.github.ryamal4.model.BookSummary
import io.github.ryamal4.service.flibusta.FlibustaClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import java.util.UUID

class SendToKindleBot(
    private val config: BotConfiguration,
    private val flibustaClient: FlibustaClient,
    private val kindleService: KindleService,
    private val dispatcher: CoroutineDispatcher
) {
    private val pageSize = 5
    private val searchSessions = mutableMapOf<String, String>()
    private val log = KotlinLogging.logger {  }

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
                if (!isAuthorized(message.from?.id)) {
                    sendUnauthorizedMessage(message.chat.id)
                    return@command
                }

                val query = args.joinToString(" ")
                if (query.isEmpty()) {
                    bot.sendMessage(
                        chatId = ChatId.fromId(message.chat.id),
                        text = "Использование: /search <название книги>"
                    )
                    return@command
                }

                withContext(dispatcher) {
                    try {
                        val books = flibustaClient.getBooks(query)

                        if (books.isEmpty()) {
                            bot.sendMessage(
                                chatId = ChatId.fromId(message.chat.id),
                                text = "Книги не найдены"
                            )
                            return@withContext
                        }
                        val page = 0
                        val totalPages = (books.size + pageSize - 1) / pageSize
                        val booksOnPage = books.take(pageSize)

                        val messageText = formatBooksPage(query, page, totalPages, books.size)
                        val keyboard = createPaginationKeyboard(getIdForQuery(query), page, totalPages, booksOnPage)

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

            command("info") {
                if (!isAuthorized(message.from?.id)) {
                    sendUnauthorizedMessage(message.chat.id)
                    return@command
                }

                val bookId = args.firstOrNull()?.toIntOrNull()
                if (bookId == null) {
                    bot.sendMessage(
                        chatId = ChatId.fromId(message.chat.id),
                        text = "Использование: /info <book_id>"
                    )
                    return@command
                }

                withContext(dispatcher) {
                    try {
                        val bookInfo = flibustaClient.getBookInfo(bookId)
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
                        log.error(e) { "Error getting book info" }
                        bot.sendMessage(
                            chatId = ChatId.fromId(this@command.message.chat.id),
                            text = "Ошибка получения информации: ${e.message}"
                        )
                    }
                }
            }

            command("send") {
                if (!isAuthorized(message.from?.id)) {
                    sendUnauthorizedMessage(message.chat.id)
                    return@command
                }

                val bookId = args.firstOrNull()?.toIntOrNull()
                if (bookId == null) {
                    bot.sendMessage(
                        chatId = ChatId.fromId(message.chat.id),
                        text = "Использование: /send <book_id>"
                    )
                    return@command
                }

                withContext(dispatcher) {
                    try {
                        bot.sendMessage(
                            chatId = ChatId.fromId(this@command.message.chat.id),
                            text = "Скачиваю книгу..."
                        )

                        val bookPath = flibustaClient.downloadBook(bookId)

                        bot.sendMessage(
                            chatId = ChatId.fromId(this@command.message.chat.id),
                            text = "Отправляю на Kindle..."
                        )

                        kindleService.sendToKindle(bookPath)

                        bot.sendMessage(
                            chatId = ChatId.fromId(this@command.message.chat.id),
                            text = "Книга отправлена на Kindle!"
                        )
                    } catch (e: Exception) {
                        log.error(e) { "Error sending book to Kindle" }
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
                        val books = flibustaClient.getBooks(query)

                        if (books.isEmpty()) {
                            bot.sendMessage(
                                chatId = ChatId.fromId(message.chat.id),
                                text = "Книги не найдены"
                            )
                            return@withContext
                        }

                        val page = 0
                        val totalPages = (books.size + pageSize - 1) / pageSize
                        val booksOnPage = books.take(pageSize)

                        val messageText = formatBooksPage(query, page, totalPages, books.size)
                        val keyboard = createPaginationKeyboard(getIdForQuery(query), page, totalPages, booksOnPage)

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
                if (!isAuthorized(callbackQuery.from.id)) {
                    callbackQuery.message?.chat?.id?.let { sendUnauthorizedMessage(it) }
                    return@callbackQuery
                }

                val data = callbackQuery.data
                log.debug { "callback query $data" }
                when {
                    data.startsWith("book_") -> {
                        val parts = data.removePrefix("book_").split("_")
                        if (parts.size == 3) {
                            val sessionId = parts[0]
                            val searchQuery = searchSessions[sessionId]
                            val page = parts[1].toIntOrNull()
                            val index = parts[2].toIntOrNull()

                            if (searchQuery == null || page == null || index == null) {
                                return@callbackQuery
                            }

                            val books = withContext(dispatcher) {
                                flibustaClient.getBooks(searchQuery)
                            }
                            val bookIndexInResults = page * pageSize + index
                            if (bookIndexInResults >= books.size) {
                                return@callbackQuery
                            }

                            val book = books[bookIndexInResults]
                            withContext(dispatcher) {
                                try {
                                    val bookInfo = flibustaClient.getBookInfo(book.id)
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
                                return@callbackQuery
                            }
                            val books = withContext(dispatcher) {
                                flibustaClient.getBooks(searchQuery)
                            }

                            val totalPages = (books.size + pageSize - 1) / pageSize
                            if (page !in 0..<totalPages) {
                                return@callbackQuery
                            }

                            val booksOnPage = books.drop(page * pageSize).take(pageSize)
                            val messageText = formatBooksPage(searchQuery, page, totalPages, books.size)
                            val keyboard = createPaginationKeyboard(sessionId, page, totalPages, booksOnPage)

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
                    data == "noop" -> {
                        bot.answerCallbackQuery(callbackQuery.id)
                    }
                    data.startsWith("info_") -> {
                        val bookId = data.substringAfter("info_").toIntOrNull()
                        if (bookId != null) {
                            withContext(dispatcher) {
                                try {
                                    val bookInfo = flibustaClient.getBookInfo(bookId)
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

                                    val bookPath = flibustaClient.downloadBook(bookId)

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
        return sessionId
    }

    private fun formatBooksPage(
        query: String,
        page: Int,
        totalPages: Int,
        totalBooks: Int
    ): String {
        return """
            Результаты поиска: "$query"
            Найдено книг: $totalBooks
            Страница ${page + 1} из $totalPages
        """.trimIndent()
    }

    private fun createPaginationKeyboard(
        sessionId: String,
        page: Int,
        totalPages: Int,
        books: List<BookSummary>
    ): InlineKeyboardMarkup {
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
            bookButtons + listOf(navigationButtons)
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
