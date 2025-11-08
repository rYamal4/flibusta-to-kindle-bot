package storage

import io.github.ryamal4.storage.UserRepository
import io.github.ryamal4.storage.Users
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File

class UserRepositoryTest : FunSpec({

    val testDbPath = "./test-${System.currentTimeMillis()}.db"
    lateinit var repository: UserRepository

    beforeSpec {
        Database.connect("jdbc:sqlite:$testDbPath", "org.sqlite.JDBC")
        transaction {
            SchemaUtils.create(Users)
        }
        repository = UserRepository()
    }

    afterTest {
        transaction {
            Users.deleteAll()
        }
    }

    afterSpec {
        File(testDbPath).delete()
    }

    test("getKindleEmail - returns null for non-existent user") {
        val result = repository.getKindleEmail(12345L)

        result shouldBe null
    }

    test("setKindleEmail - creates new user with email") {
        val userId = 12345L
        val email = "test@kindle.com"

        repository.setKindleEmail(userId, email)

        val result = repository.getKindleEmail(userId)
        result shouldBe email
    }

    test("setKindleEmail - updates existing user email") {
        val userId = 12345L
        val firstEmail = "first@kindle.com"
        val secondEmail = "second@kindle.com"

        repository.setKindleEmail(userId, firstEmail)
        repository.setKindleEmail(userId, secondEmail)

        val result = repository.getKindleEmail(userId)
        result shouldBe secondEmail
    }

    test("setKindleEmail - creates multiple users independently") {
        val user1Id = 11111L
        val user2Id = 22222L
        val email1 = "user1@kindle.com"
        val email2 = "user2@kindle.com"

        repository.setKindleEmail(user1Id, email1)
        repository.setKindleEmail(user2Id, email2)

        repository.getKindleEmail(user1Id) shouldBe email1
        repository.getKindleEmail(user2Id) shouldBe email2
    }

    test("setKindleEmail - handles long email addresses") {
        val userId = 12345L
        val longEmail = "very.long.email.address.that.might.be.used.in.real.world@kindle.com"

        repository.setKindleEmail(userId, longEmail)

        val result = repository.getKindleEmail(userId)
        result shouldBe longEmail
    }

    test("getKindleEmail - returns correct email after update") {
        val userId = 12345L
        val emails = listOf("first@kindle.com", "second@kindle.com", "third@kindle.com")

        emails.forEach { email ->
            repository.setKindleEmail(userId, email)
            repository.getKindleEmail(userId) shouldBe email
        }
    }

    test("setKindleEmail - overwrites previous email completely") {
        val userId = 12345L
        val firstEmail = "verylongemail@kindle.com"
        val secondEmail = "short@k.com"

        repository.setKindleEmail(userId, firstEmail)
        repository.setKindleEmail(userId, secondEmail)

        val result = repository.getKindleEmail(userId)
        result shouldBe secondEmail
        result shouldNotBe firstEmail
    }

    test("getKindleEmail - handles different user IDs") {
        val userIds = listOf(1L, 100L, 999999L)
        val email = "test@kindle.com"

        userIds.forEach { userId ->
            repository.setKindleEmail(userId, email)
        }

        userIds.forEach { userId ->
            repository.getKindleEmail(userId) shouldBe email
        }
    }

    test("setKindleEmail - handles email with special characters") {
        val userId = 12345L
        val email = "user+test@kindle.co.uk"

        repository.setKindleEmail(userId, email)

        val result = repository.getKindleEmail(userId)
        result shouldBe email
    }
})
