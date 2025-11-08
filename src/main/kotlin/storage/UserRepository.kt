package io.github.ryamal4.storage

import mu.KotlinLogging
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Instant

class UserRepository : IUserRepository {
    private val log = KotlinLogging.logger {}

    override fun getKindleEmail(userId: Long): String? {
        return transaction {
            Users.selectAll().where { Users.userId eq userId }
                .singleOrNull()
                ?.get(Users.kindleEmail)
        }
    }

    override fun setKindleEmail(userId: Long, email: String) {
        val timestamp = Instant.now().toString()

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
}