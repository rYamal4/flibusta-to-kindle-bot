package io.github.ryamal4.service

import java.nio.file.Path

interface IKindleService {
    suspend fun sendToKindle(book: Path, kindleEmail: String)
}