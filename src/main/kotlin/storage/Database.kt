package io.github.ryamal4.storage

import mu.KotlinLogging
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File

private val log = KotlinLogging.logger {}

object Users : Table("users") {
    val userId = long("user_id")
    val kindleEmail = varchar("kindle_email", 255)
    val createdAt = varchar("created_at", 50)
    val updatedAt = varchar("updated_at", 50)

    override val primaryKey = PrimaryKey(userId)
}

fun initDatabase() {
    val dbFile = File("./data")
    if (!dbFile.exists()) {
        dbFile.mkdirs()
        log.debug { "Created data directory" }
    }

    Database.connect("jdbc:sqlite:./data/bot.db", "org.sqlite.JDBC")
    log.debug { "Connected to SQLite database" }

    transaction {
        SchemaUtils.create(Users)
        log.debug { "Database schema initialized" }
    }
}

