package io.github.ryamal4.service

import jakarta.activation.FileDataSource
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.simplejavamail.api.mailer.config.TransportStrategy
import org.simplejavamail.email.EmailBuilder
import org.simplejavamail.mailer.MailerBuilder
import java.nio.file.Path
import kotlin.coroutines.CoroutineContext
import kotlin.io.path.name

class KindleService(
    val smtp: String,
    val senderEmail: String,
    val senderPassword: String,
    val kindleEmail: String,
    val dispatcher: CoroutineContext
) : IKindleService {
    private val log = KotlinLogging.logger {  }

    override suspend fun sendToKindle(path: Path) {
        val fileSize = path.toFile().length()
        val fileSizeKB = fileSize / 1024
        log.info { "Preparing to send book ${path.name} (${fileSizeKB}KB) to $kindleEmail" }

        val (host, port) = smtp.split(":").let {
            it[0] to it[1].toInt()
        }
        log.debug { "Connecting to SMTP server $host:$port" }

        val startTime = System.currentTimeMillis()

        val email = EmailBuilder.startingBlank()
            .from(senderEmail)
            .to(kindleEmail)
            .withSubject("Send to Kindle")
            .withAttachment(path.name, FileDataSource(path.toFile()))
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
                log.info { "Successfully sent ${path.name} (${fileSizeKB}KB) to $kindleEmail in ${duration}ms" }
            }.onFailure { exception ->
                val duration = System.currentTimeMillis() - startTime
                log.error(exception) { "Failed to send ${path.name} to $kindleEmail after ${duration}ms" }
                throw exception
            }
        }
    }
}