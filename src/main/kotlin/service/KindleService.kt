package io.github.ryamal4.service

import jakarta.activation.FileDataSource
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.simplejavamail.api.mailer.config.TransportStrategy
import org.simplejavamail.email.EmailBuilder
import org.simplejavamail.mailer.MailerBuilder
import java.nio.file.Path
import kotlin.coroutines.CoroutineContext
import kotlin.io.path.extension
import kotlin.io.path.name

class KindleService(
    val smtp: String,
    val senderEmail: String,
    val senderPassword: String,
    val dispatcher: CoroutineContext
) : IKindleService {
    private val log = KotlinLogging.logger { }
    private val allowedExtensions = listOf(
        "pdf", "doc", "docx", "txt", "rtf", "htm", "html", "png", "gif", "jpg",
        "jpeg", "bmp", "epub"
    )

    override suspend fun sendToKindle(book: Path, kindleEmail: String) {
        require(book.extension in allowedExtensions) {
            "not allowed extension: ${book.extension}"
        }

        val fileSize = book.toFile().length()
        val fileSizeKB = fileSize / 1024
        log.info { "Preparing to send book ${book.name} (${fileSizeKB}KB) to $kindleEmail" }

        val (host, port) = smtp.split(":").let {
            it[0] to it[1].toInt()
        }
        log.debug { "Connecting to SMTP server $host:$port" }

        val startTime = System.currentTimeMillis()

        val email = EmailBuilder.startingBlank()
            .from(senderEmail)
            .to(kindleEmail)
            .withSubject("Send to Kindle")
            .withAttachment(book.name, FileDataSource(book.toFile()))
            .buildEmail()

        val mailer = MailerBuilder
            .withSMTPServer(host, port, senderEmail, senderPassword)
            .withTransportStrategy(TransportStrategy.SMTP_TLS)
            .buildMailer()

        withContext(dispatcher) {
            runCatching {
                mailer.sendMail(email)
            }.onSuccess {
                val duration = System.currentTimeMillis() - startTime
                log.info { "Successfully sent ${book.name} (${fileSizeKB}KB) to $kindleEmail in ${duration}ms" }
            }.onFailure {
                val duration = System.currentTimeMillis() - startTime
                log.error(it) { "Failed to send ${book.name} to $kindleEmail after ${duration}ms" }
                throw it
            }
        }
    }
}