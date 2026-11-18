package ru.kardiomonitor.staff.shared

import java.text.Normalizer
import java.util.Locale

fun canonicalTextKey(value: String): String =
    Normalizer.normalize(value, Normalizer.Form.NFC).lowercase(Locale.ROOT)
