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
        log.info { "Sending book ${path.name} to Kindle email $kindleEmail" }
        val (host, port) = smtp.split(":").let {
            it[0] to it[1].toInt()
        }

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
                log.info { "book sent successfully" }
            }.onFailure {
                log.error { "error while sending book" }
            }
        }
        log.info { "Book ${path.name} sent successfully to $kindleEmail" }
    }
}