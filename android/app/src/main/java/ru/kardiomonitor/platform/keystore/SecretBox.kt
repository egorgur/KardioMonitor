package ru.kardiomonitor.platform.keystore

/** Шифрование небольших секретов ключом, который не покидает Android Keystore. */
interface SecretBox {
    fun seal(plainText: ByteArray): SealedSecret

    fun open(secret: SealedSecret): ByteArray
}
