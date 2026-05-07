package com.example.nfcdemo

import android.nfc.FormatException
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import java.io.IOException
import java.nio.charset.StandardCharsets

const val NFC_MIME_TYPE = "application/vnd.com.example.nfcdemo"
const val NFC_PACKAGE_NAME = "com.example.nfcdemo"

data class CardData(
    val name: String = "",
    val phone: String = ""
)

fun buildNdefMessage(name: String, phone: String): NdefMessage {
    val json = """{"name":"${escape(name)}","phone":"${escape(phone)}"}"""
    val mimeRecord = NdefRecord.createMime(NFC_MIME_TYPE, json.toByteArray(StandardCharsets.UTF_8))
    val aarRecord = NdefRecord.createApplicationRecord(NFC_PACKAGE_NAME)
    return NdefMessage(arrayOf(mimeRecord, aarRecord))
}

fun parseNdefMessage(message: NdefMessage?): CardData {
    message ?: return CardData()
    for (record in message.records) {
        if (record.tnf == NdefRecord.TNF_MIME_MEDIA) {
            val type = String(record.type, StandardCharsets.US_ASCII)
            if (NFC_MIME_TYPE.equals(type, ignoreCase = true)) {
                val json = String(record.payload, StandardCharsets.UTF_8)
                return parseJson(json)
            }
        }
    }
    return CardData()
}

@Throws(IOException::class, FormatException::class)
fun writeTag(tag: Tag?, message: NdefMessage) {
    if (tag == null) throw IOException("Tag is null")

    Ndef.get(tag)?.let { ndef ->
        ndef.use {
            it.connect()
            if (!it.isWritable) throw IOException("Tag is read-only")
            if (it.maxSize < message.toByteArray().size) throw IOException("Tag capacity is too small")
            it.writeNdefMessage(message)
        }
        return
    }

    NdefFormatable.get(tag)?.let { formatable ->
        formatable.use {
            it.connect()
            it.format(message)
        }
        return
    }

    throw IOException("Tag does not support NDEF")
}

private fun escape(s: String?): String = if (s == null) "" else buildString(s.length) {
    for (c in s) {
        when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(c)
        }
    }
}

private fun parseJson(json: String): CardData =
    CardData(extract(json, "name"), extract(json, "phone"))

private fun extract(json: String, key: String): String {
    val regex = """"${Regex.escape(key)}"\s*:\s*"((?:\\.|[^"\\])*)"""".toRegex()
    val match = regex.find(json) ?: return ""
    return unescape(match.groupValues[1])
}

private fun unescape(s: String): String = buildString(s.length) {
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '\\' && i + 1 < s.length) {
            when (s[i + 1]) {
                'n' -> append('\n')
                'r' -> append('\r')
                't' -> append('\t')
                '"' -> append('"')
                '\\' -> append('\\')
                else -> append(s[i + 1])
            }
            i += 2
        } else {
            append(c)
            i++
        }
    }
}
