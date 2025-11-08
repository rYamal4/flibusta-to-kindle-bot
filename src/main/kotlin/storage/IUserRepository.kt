package io.github.ryamal4.storage

interface IUserRepository {
    fun getKindleEmail(userId: Long): String?

    fun setKindleEmail(userId: Long, email: String)
}