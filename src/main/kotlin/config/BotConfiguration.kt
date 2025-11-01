package io.github.ryamal4.config

import io.github.cdimascio.dotenv.dotenv

data class BotConfiguration(
    val telegramBotToken: String,
    val telegramUserId: Long,
    val kindleEmail: String,
    val smtp: String,
    val senderEmail: String,
    val senderPassword: String,
    val flibustaUrl: String
) {
    companion object {
        fun fromEnv(): BotConfiguration {
            val dotenv = dotenv {
                ignoreIfMissing = true
            }

            return BotConfiguration(
                telegramBotToken = getEnv(dotenv, "TELEGRAM_BOT_TOKEN"),
                telegramUserId = getEnv(dotenv, "TELEGRAM_USER_ID").toLong(),
                kindleEmail = getEnv(dotenv, "KINDLE_EMAIL"),
                smtp = getEnv(dotenv, "SMTP_HOST"),
                senderEmail = getEnv(dotenv, "SENDER_EMAIL"),
                senderPassword = getEnv(dotenv, "SENDER_PASSWORD"),
                flibustaUrl = getEnv(dotenv, "FLIBUSTA_URL")
            )
        }

        private fun getEnv(dotenv: io.github.cdimascio.dotenv.Dotenv, name: String): String {
            return dotenv[name] ?: System.getenv(name) ?: throw IllegalStateException("Environment variable $name is not set")
        }
    }
}
