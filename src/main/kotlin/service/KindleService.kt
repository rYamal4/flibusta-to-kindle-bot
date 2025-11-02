package io.github.ryamal4.service

import jakarta.activation.FileDataSource
import mu.KotlinLogging
import org.simplejavamail.api.mailer.config.TransportStrategy
import org.simplejavamail.email.EmailBuilder
import org.simplejavamail.mailer.MailerBuilder
import java.nio.file.Path
import kotlin.io.path.name

class KindleService(
    val smtp: String,
    val senderEmail: String,
    val senderPassword: String,
    val kindleEmail: String
) : IKindleService {
    private val log = KotlinLogging.logger {  }

    override fun sendToKindle(path: Path) {
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
            .withTransportStrategy(TransportStrategy.SMTP)
            .buildMailer()

        mailer.sendMail(email)
        log.info { "Book ${path.name} sent successfully to $kindleEmail" }
    }
}