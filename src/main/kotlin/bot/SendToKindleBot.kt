package io.github.ryamal4.bot

import com.github.kotlintelegrambot.bot
import com.github.kotlintelegrambot.dispatch
import com.github.kotlintelegrambot.dispatcher.command
import com.github.kotlintelegrambot.dispatcher.callbackQuery
import com.github.kotlintelegrambot.dispatcher.text
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.InlineKeyboardMarkup
import com.github.kotlintelegrambot.entities.keyboard.InlineKeyboardButton
import io.github.ryamal4.service.FlibustaClient
import io.github.ryamal4.config.BotConfiguration
import io.github.ryamal4.service.KindleService
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging

class SendToKindleBot(
    private val config: BotConfiguration,
    private val flibustaClient: FlibustaClient,
    private val kindleService: KindleService
) {
    private val log = KotlinLogging.logger {  }

    private val bot = bot {
        token = config.telegramBotToken

        dispatch {
            command("start") {
                if (!isAuthorized(message.from?.id)) return@command

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
                if (!isAuthorized(message.from?.id)) return@command

                val query = args.joinToString(" ")
                if (query.isEmpty()) {
                    bot.sendMessage(
                        chatId = ChatId.fromId(message.chat.id),
                        text = "Использование: /search <название книги>"
                    )
                    return@command
                }

                runBlocking {
                    try {
                        val books = flibustaClient.getBooks(query)

                        if (books.isEmpty()) {
                            bot.sendMessage(
                                chatId = ChatId.fromId(message.chat.id),
                                text = "Книги не найдены"
                            )
                            return@runBlocking
                        }

                        books.take(10).forEach { book ->
                            val keyboard = InlineKeyboardMarkup.create(
                                listOf(
                                    InlineKeyboardButton.CallbackData(
                                        text = "Инфо",
                                        callbackData = "info_${book.id}"
                                    ),
                                    InlineKeyboardButton.CallbackData(
                                        text = "На Kindle",
                                        callbackData = "send_${book.id}"
                                    )
                                )
                            )

                            bot.sendMessage(
                                chatId = ChatId.fromId(message.chat.id),
                                text = "${book.title}\nАвтор: ${book.author}\nID: ${book.id}",
                                replyMarkup = keyboard
                            )
                        }
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
                if (!isAuthorized(message.from?.id)) return@command

                val bookId = args.firstOrNull()?.toIntOrNull()
                if (bookId == null) {
                    bot.sendMessage(
                        chatId = ChatId.fromId(message.chat.id),
                        text = "Использование: /info <book_id>"
                    )
                    return@command
                }

                runBlocking {
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
                if (!isAuthorized(message.from?.id)) return@command

                val bookId = args.firstOrNull()?.toIntOrNull()
                if (bookId == null) {
                    bot.sendMessage(
                        chatId = ChatId.fromId(message.chat.id),
                        text = "Использование: /send <book_id>"
                    )
                    return@command
                }

                runBlocking {
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
                if (!isAuthorized(message.from?.id)) return@text

                val query = text.trim()
                if (query.isEmpty()) return@text

                runBlocking {
                    try {
                        val books = flibustaClient.getBooks(query)

                        if (books.isEmpty()) {
                            bot.sendMessage(
                                chatId = ChatId.fromId(message.chat.id),
                                text = "Книги не найдены"
                            )
                            return@runBlocking
                        }

                        books.take(10).forEach { book ->
                            val keyboard = InlineKeyboardMarkup.create(
                                listOf(
                                    InlineKeyboardButton.CallbackData(
                                        text = "Инфо",
                                        callbackData = "info_${book.id}"
                                    ),
                                    InlineKeyboardButton.CallbackData(
                                        text = "На Kindle",
                                        callbackData = "send_${book.id}"
                                    )
                                )
                            )

                            bot.sendMessage(
                                chatId = ChatId.fromId(message.chat.id),
                                text = "${book.title}\nАвтор: ${book.author}\nID: ${book.id}",
                                replyMarkup = keyboard
                            )
                        }
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
                if (!isAuthorized(callbackQuery.from.id)) return@callbackQuery

                val data = callbackQuery.data
                when {
                    data.startsWith("info_") -> {
                        val bookId = data.substringAfter("info_").toIntOrNull()
                        if (bookId != null) {
                            runBlocking {
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
                                        chatId = ChatId.fromId(callbackQuery.message?.chat?.id ?: return@runBlocking),
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
                            runBlocking {
                                try {
                                    bot.sendMessage(
                                        chatId = ChatId.fromId(callbackQuery.message?.chat?.id ?: return@runBlocking),
                                        text = "Скачиваю книгу..."
                                    )

                                    val bookPath = flibustaClient.downloadBook(bookId)

                                    bot.sendMessage(
                                        chatId = ChatId.fromId(callbackQuery.message?.chat?.id ?: return@runBlocking),
                                        text = "Отправляю на Kindle..."
                                    )

                                    kindleService.sendToKindle(bookPath)

                                    bot.sendMessage(
                                        chatId = ChatId.fromId(callbackQuery.message?.chat?.id ?: return@runBlocking),
                                        text = "Книга отправлена на Kindle!"
                                    )
                                } catch (e: Exception) {
                                    log.error(e) { "Error sending book to Kindle" }
                                    bot.sendMessage(
                                        chatId = ChatId.fromId(callbackQuery.message?.chat?.id ?: return@runBlocking),
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

    private fun isAuthorized(userId: Long?): Boolean {
        return userId == config.telegramUserId
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
