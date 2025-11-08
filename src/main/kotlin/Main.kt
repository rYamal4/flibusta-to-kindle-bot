package io.github.ryamal4

import io.github.ryamal4.bot.SendToKindleBot
import io.github.ryamal4.config.BotConfiguration
import io.github.ryamal4.service.KindleService
import io.github.ryamal4.service.flibusta.FlibustaService
import io.github.ryamal4.storage.UserRepository
import io.github.ryamal4.storage.initDatabase
import kotlinx.coroutines.Dispatchers
import mu.KotlinLogging

fun main() {
    val log = KotlinLogging.logger {}
    val dispatcher = Dispatchers.IO

    try {
        log.info { "Initializing database..." }
        initDatabase()

        log.info { "Initializing user repository..." }
        val userRepository = UserRepository()

        log.info { "Loading configuration from environment variables..." }
        val config = BotConfiguration.fromEnv()

        log.info { "Initializing Flibusta client..." }
        val flibustaClient = FlibustaService(config.flibustaUrl)

        log.info { "Initializing Kindle service..." }
        val kindleService = KindleService(
            smtp = config.smtp,
            senderEmail = config.senderEmail,
            senderPassword = config.senderPassword,
            dispatcher = dispatcher
        )

        log.info { "Creating Telegram bot..." }
        val bot = SendToKindleBot(config, flibustaClient, kindleService, dispatcher, userRepository)

        Runtime.getRuntime().addShutdownHook(Thread {
            log.info { "Shutting down bot..." }
            bot.stop()
        })

        bot.start()
        log.info { "Bot started successfully!" }
    } catch (e: Exception) {
        log.error(e) { "Failed to start bot" }
        throw e
    }
}
