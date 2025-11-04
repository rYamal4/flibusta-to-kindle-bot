package io.github.ryamal4.config

import io.github.cdimascio.dotenv.dotenv
import mu.KotlinLogging

data class BotConfiguration(
    val telegramBotToken: String,
    val telegramUserId: Long,
    val smtp: String,
    val senderEmail: String,
    val senderPassword: String,
    val flibustaUrl: String,
    val isPublicBot: Boolean
) {
    companion object {
        private val defaultValues = mapOf("IS_PUBLIC_BOT" to "true", "TELEGRAM_USER_ID" to "-1")
        private val log = KotlinLogging.logger { }

        fun fromEnv(): BotConfiguration {
            log.info { "Loading bot configuration from environment variables" }

            val dotenv = dotenv {
                ignoreIfMissing = true
            }

            val config = BotConfiguration(
                telegramBotToken = getEnv(dotenv, "TELEGRAM_BOT_TOKEN"),
                telegramUserId = getEnv(dotenv, "TELEGRAM_USER_ID").toLong(),
                smtp = getEnv(dotenv, "SMTP_HOST"),
                senderEmail = getEnv(dotenv, "SENDER_EMAIL"),
                senderPassword = getEnv(dotenv, "SENDER_PASSWORD"),
                flibustaUrl = getEnv(dotenv, "FLIBUSTA_URL"),
                isPublicBot = getEnv(dotenv, "IS_SINGLE_USER").toBoolean()
            )

            log.info { "Configuration loaded successfully: userId=${config.telegramUserId}, smtp=${config.smtp}, flibusta=${config.flibustaUrl}" }
            return config
        }

        private fun getEnv(dotenv: io.github.cdimascio.dotenv.Dotenv, name: String): String {
            return dotenv[name] ?: System.getenv(name) ?: run {
                defaultValues[name]?.let {
                    return it
                }

                log.error { "Missing required environment variable: $name" }
                throw IllegalStateException("Environment variable $name is not set")
            }
        }
    }

}
