package com.waph1.markithub.util

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import com.waph1.markithub.model.Contact
import com.waph1.markithub.model.TypedValue
import com.waph1.markithub.model.TypedAddress
import ezvcard.Ezvcard
import ezvcard.VCard
import ezvcard.parameter.AddressType
import ezvcard.parameter.EmailType
import ezvcard.parameter.TelephoneType
import java.io.InputStream

object VCardParser {
    fun parseVCard(inputStream: InputStream): List<Contact> {
        val vcards = Ezvcard.parse(inputStream).all()
        return vcards.map { vcard -> vcardToContact(vcard) }
    }

    fun parseVCardFromUri(context: Context, uri: Uri): List<Contact> {
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.let { android.os.ParcelFileDescriptor.AutoCloseInputStream(it) }?.use { inputStream ->
                parseVCard(inputStream)
            } ?: emptyList()
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    private fun vcardToContact(vcard: VCard): Contact {
        // Phones with type mapping
        val phones = vcard.telephoneNumbers.map { tel ->
            val androidType = when {
                tel.types.contains(TelephoneType.CELL) -> ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE
                tel.types.contains(TelephoneType.HOME) -> ContactsContract.CommonDataKinds.Phone.TYPE_HOME
                tel.types.contains(TelephoneType.WORK) -> ContactsContract.CommonDataKinds.Phone.TYPE_WORK
                tel.types.contains(TelephoneType.FAX) -> ContactsContract.CommonDataKinds.Phone.TYPE_FAX_WORK
                tel.types.contains(TelephoneType.PAGER) -> ContactsContract.CommonDataKinds.Phone.TYPE_PAGER
                else -> ContactsContract.CommonDataKinds.Phone.TYPE_OTHER
            }
            TypedValue(tel.text, androidType)
        }

        // Emails with type mapping
        val emails = vcard.emails.map { email ->
            val androidType = when {
                email.types.contains(EmailType.HOME) -> ContactsContract.CommonDataKinds.Email.TYPE_HOME
                email.types.contains(EmailType.WORK) -> ContactsContract.CommonDataKinds.Email.TYPE_WORK
                else -> ContactsContract.CommonDataKinds.Email.TYPE_OTHER
            }
            TypedValue(email.value, androidType)
        }

        // Addresses
        val addresses = vcard.addresses.map { addr ->
            val androidType = when {
                addr.types.contains(AddressType.HOME) -> ContactsContract.CommonDataKinds.StructuredPostal.TYPE_HOME
                addr.types.contains(AddressType.WORK) -> ContactsContract.CommonDataKinds.StructuredPostal.TYPE_WORK
                else -> ContactsContract.CommonDataKinds.StructuredPostal.TYPE_OTHER
            }
            TypedAddress(
                street = addr.streetAddress ?: "",
                city = addr.locality ?: "",
                region = addr.region ?: "",
                postcode = addr.postalCode ?: "",
                country = addr.country ?: "",
                type = androidType
            )
        }

        // Birthday — extract as YYYY-MM-DD string
        val birthday = vcard.birthday?.let { bday ->
            bday.text ?: try {
                // bday.date is a Temporal — format it manually
                val d = bday.date
                if (d is java.time.LocalDate) d.toString()
                else bday.partialDate?.let { pd ->
                    String.format("%04d-%02d-%02d", pd.year ?: 0, pd.month ?: 1, pd.date ?: 1)
                }
            } catch (_: Exception) { null }
        }

        // Websites
        val websites = vcard.urls.map { it.value }

        return Contact(
            id = java.util.UUID.randomUUID().toString(),
            firstName = vcard.structuredName?.given ?: "",
            lastName = vcard.structuredName?.family ?: "",
            displayName = vcard.formattedName?.value ?: "",
            phoneNumbers = phones,
            emails = emails,
            addresses = addresses,
            organization = vcard.organization?.values?.firstOrNull() ?: "",
            note = vcard.notes.firstOrNull()?.value ?: "",
            birthday = birthday,
            websites = websites
        )
    }
}
