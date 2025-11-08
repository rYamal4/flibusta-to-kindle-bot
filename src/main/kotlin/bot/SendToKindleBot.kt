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
import io.github.ryamal4.storage.UserRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import java.util.*

class SendToKindleBot(
    private val config: BotConfiguration,
    private val flibustaService: FlibustaService,
    private val kindleService: KindleService,
    private val dispatcher: CoroutineDispatcher,
    private val userRepository: UserRepository
) {
    private val pageSize = 10
    private val searchSessions = mutableMapOf<String, String>()
    private val log = KotlinLogging.logger { }

    private val bot = bot {
        token = config.telegramBotToken

        dispatch {
            command("start") {
                val userId = message.from?.id
                if (!isAuthorized(userId)) {
                    log.warn { "Unauthorized start attempt by user $userId" }
                    sendUnauthorizedMessage(message.chat.id)
                    return@command
                }
                val emailInfo = userRepository.getKindleEmail(config.telegramUserId).let {
                    if (it == null) "Вы не добавили почту" else "Ваша почта: $it"
                }

                bot.sendMessage(
                    chatId = ChatId.fromId(message.chat.id),
                    text = """
                    Чтобы найти книгу, просто напишите название книги и отправьте сообщение боту.

                    Перед использованием бота, установите почту, связанную с вашим аккаунтом Kindle с помощью команды /email и добавьте ${config.senderEmail} в список одобренных email адресов на странице настроек устройства в разделе Personal Document Settings.

                    Команды:

                    /start - информация о боте
                    /email your@kindle.com - добавление почты вашего аккаунта Kindle

                    """.plus(emailInfo).trimIndent()
                )
            }

            command("email") {
                val userId = message.from?.id
                if (!isAuthorized(userId)) {
                    log.warn { "Unauthorized email attempt by user $userId" }
                    sendUnauthorizedMessage(message.chat.id)
                    return@command
                }

                val email = args.firstOrNull()?.trim()
                if (email.isNullOrEmpty()) {
                    bot.sendMessage(
                        chatId = ChatId.fromId(message.chat.id),
                        text = "Использование: /email your@kindle.com"
                    )
                    return@command
                }

                val emailRegex = Regex("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")
                if (!emailRegex.matches(email)) {
                    bot.sendMessage(
                        chatId = ChatId.fromId(message.chat.id),
                        text = "Неверный формат email. Пример: user@kindle.com"
                    )
                    return@command
                }

                userRepository.setKindleEmail(userId!!, email)
                log.info { "User $userId set Kindle email to $email" }

                bot.sendMessage(
                    chatId = ChatId.fromId(message.chat.id),
                    text = "Kindle email установлен: $email"
                )
            }

            text {
                if (!isAuthorized(message.from?.id)) {
                    sendUnauthorizedMessage(message.chat.id)
                    return@text
                }

                val query = text.trim()
                if (text.startsWith("/") || query.isEmpty()) {
                    return@text
                }

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

                        val sequencesOnPage = searchResults.sequences.take(pageSize)
                        val booksStartIndex = maxOf(0, 0 - searchResults.sequences.size)
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

                            val sequenceStartIndex = page * pageSize
                            val sequencesOnPage = sequences.drop(sequenceStartIndex).take(pageSize)
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
                            val userEmail = userRepository.getKindleEmail(userId)
                            if (userEmail == null) {
                                log.warn { "User $userId attempted to send book without setting Kindle email" }
                                bot.sendMessage(
                                    chatId = ChatId.fromId(callbackQuery.message?.chat?.id ?: return@callbackQuery),
                                    text = "Сначала установите Kindle email через /email your@kindle.com"
                                )
                                return@callbackQuery
                            }

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

                                    kindleService.sendToKindle(bookPath, userEmail)
                                    log.info { "User $userId successfully sent book $bookId to $userEmail" }

                                    bot.sendMessage(
                                        chatId = ChatId.fromId(callbackQuery.message?.chat?.id ?: return@withContext),
                                        text = "Книга отправлена на Kindle!"
                                    )
                                } catch (e: Exception) {
                                    log.error(e) { "User $userId: Error sending book $bookId to Kindle" }
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
        return config.isPublicBot || userId == config.telegramUserId
    }

    private fun sendUnauthorizedMessage(chatId: Long) {
        bot.sendMessage(
            chatId = ChatId.fromId(chatId),
            text = """
                Вы не можете пользоваться ботом.

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
