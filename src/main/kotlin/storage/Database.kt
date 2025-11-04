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

fun getUserKindleEmail(userId: Long): String? {
    return transaction {
        Users.selectAll().where { Users.userId eq userId }
            .singleOrNull()
            ?.get(Users.kindleEmail)
    }
}

fun setUserKindleEmail(userId: Long, email: String) {
    val timestamp = java.time.Instant.now().toString()

    transaction {
        val existingUser = Users.selectAll().where { Users.userId eq userId }.singleOrNull()

        if (existingUser != null) {
            Users.update({ Users.userId eq userId }) {
                it[kindleEmail] = email
                it[updatedAt] = timestamp
            }
            log.info { "Updated Kindle email for user $userId" }
        } else {
            Users.insert {
                it[Users.userId] = userId
                it[kindleEmail] = email
                it[createdAt] = timestamp
                it[updatedAt] = timestamp
            }
            log.info { "Created new user $userId with Kindle email" }
        }
    }
}