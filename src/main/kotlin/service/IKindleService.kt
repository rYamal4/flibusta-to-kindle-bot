package io.github.ryamal4.service

import java.nio.file.Path

interface IKindleService {
    fun sendToKindle(path: Path)
}