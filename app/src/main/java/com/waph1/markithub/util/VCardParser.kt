package com.waph1.markithub.util

import android.content.Context
import android.net.Uri
import com.waph1.markithub.model.Contact
import ezvcard.Ezvcard
import ezvcard.VCard
import java.io.InputStream

object VCardParser {
    fun parseVCard(inputStream: InputStream): List<Contact> {
        val vcards = Ezvcard.parse(inputStream).all()
        return vcards.map { vcard ->
            Contact(
                id = java.util.UUID.randomUUID().toString(),
                firstName = vcard.structuredName?.given ?: "",
                lastName = vcard.structuredName?.family ?: "",
                displayName = vcard.formattedName?.value ?: "",
                phoneNumbers = vcard.telephoneNumbers.map { it.text },
                emails = vcard.emails.map { it.value },
                organization = vcard.organization?.values?.firstOrNull() ?: "",
                note = vcard.notes.firstOrNull()?.value ?: ""
            )
        }
    }

    fun parseVCardFromUri(context: Context, uri: Uri): List<Contact> {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                parseVCard(inputStream)
            } ?: emptyList()
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }
}
