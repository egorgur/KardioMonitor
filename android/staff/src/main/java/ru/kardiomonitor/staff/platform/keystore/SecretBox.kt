package ru.kardiomonitor.staff.platform.keystore

interface SecretBox {
    fun seal(plainText: ByteArray): ByteArray

    fun open(sealed: ByteArray): ByteArray
}
