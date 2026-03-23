package com.waph1.markithub.util

import android.accounts.Account
import android.accounts.AccountManager
import android.content.*
import android.net.Uri
import android.provider.ContactsContract
import androidx.documentfile.provider.DocumentFile
import com.waph1.markithub.model.Contact
import com.waph1.markithub.model.TypedValue
import com.waph1.markithub.model.TypedAddress
import ezvcard.parameter.AddressType
import ezvcard.parameter.EmailType
import ezvcard.parameter.TelephoneType
import java.security.MessageDigest
import java.util.ArrayList

object ContactSyncEngine {
    private const val ACCOUNT_TYPE = "com.waph1.markithub.contacts"
    private const val ACCOUNT_NAME = "MarkItHub Contacts"

    // MIME types we manage — everything else is left intact on update
    private val MANAGED_MIMETYPES = arrayOf(
        ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
        ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
        ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE,
        ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE,
        ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE,
        ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE,
        ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE,
        ContactsContract.CommonDataKinds.Website.CONTENT_ITEM_TYPE
    )

    fun sync(context: Context, folderUri: Uri) {
        ensureAccountExists(context)
        val groupId = ensureDefaultGroupExists(context)

        SyncLogger.log(context, "contacts", "Syncing contacts...")
        val folder = DocumentFile.fromTreeUri(context, folderUri)
        if (folder == null || !folder.isDirectory) {
            SyncLogger.log(context, "contacts", "Sync failed: folder invalid")
            return
        }

        // 1. Reverse sync: system → file (commit local edits first)
        reverseSync(context, folderUri)

        // 2. Handle system deletions: system (deleted) → file (delete)
        handleSystemDeletions(context, folderUri)

        // 3. Forward sync: file → system
        val systemContacts = getSystemContacts(context)
        val vcfFiles = folder.listFiles().filter { it.name?.endsWith(".vcf", ignoreCase = true) == true }
        val filesFound = vcfFiles.mapNotNull { it.name }.toSet()

        val ops = ArrayList<ContentProviderOperation>()
        fun flushOps() {
            if (ops.size >= 100) {
                try { context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops) }
                catch (e: Exception) { SyncLogger.log(context, "contacts", "Batch error: ${e.message}") }
                ops.clear()
            }
        }

        vcfFiles.forEach { file ->
            val fileName = file.name!!
            val contacts = VCardParser.parseVCardFromUri(context, file.uri)
            contacts.forEach { contact ->
                val (rawContactId, existingHash) = systemContacts[fileName] ?: Pair(null, null)
                val newHash = contact.dataHash()

                if (rawContactId == null) {
                    addInsertContactOps(ops, contact, fileName, groupId, newHash)
                } else if (existingHash != newHash) {
                    addUpdateContactOps(ops, contact, rawContactId, groupId, newHash)
                }
                flushOps()
            }
        }

        // Remove system contacts whose file was deleted
        systemContacts.forEach { (fileName, rawData) ->
            if (fileName !in filesFound) {
                ops.add(ContentProviderOperation.newDelete(syncAdapterUri(ContactsContract.RawContacts.CONTENT_URI))
                    .withSelection("${ContactsContract.RawContacts._ID}=?", arrayOf(rawData.first.toString()))
                    .build())
                SyncLogger.log(context, "contacts", "Removed: $fileName")
                flushOps()
            }
        }

        if (ops.isNotEmpty()) {
            try { context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops) }
            catch (e: Exception) { SyncLogger.log(context, "contacts", "Final batch error: ${e.message}") }
        }

        SyncLogger.log(context, "contacts", "Sync complete.")

        // ── Post-sync file health repair pass ────────────────────
        performRepairPass(context, folderUri)
    }

    // ── Reverse sync: system changes → VCF files ─────────────────────────

    private fun reverseSync(context: Context, folderUri: Uri) {
        val resolver = context.contentResolver
        val folder = DocumentFile.fromTreeUri(context, folderUri) ?: return

        val selection = "${ContactsContract.RawContacts.ACCOUNT_TYPE}=? AND " +
                "(${ContactsContract.RawContacts.DIRTY}=1 OR ${ContactsContract.RawContacts.SYNC1} IS NULL) AND " +
                "${ContactsContract.RawContacts.DELETED}=0"

        val ops = ArrayList<ContentProviderOperation>()
        resolver.query(
            syncAdapterUri(ContactsContract.RawContacts.CONTENT_URI),
            arrayOf(ContactsContract.RawContacts._ID, ContactsContract.RawContacts.SYNC1, ContactsContract.RawContacts.DISPLAY_NAME_PRIMARY),
            selection, arrayOf(ACCOUNT_TYPE), null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val existingFileName = cursor.getString(1)
                val displayName = cursor.getString(2) ?: "Unnamed"

                // Deterministic filename: sanitized name, collision-checked
                val fileName = existingFileName ?: generateFileName(folder, displayName)

                if (exportContactToFile(context, id, folder, fileName)) {
                    val contact = fetchContactFromSystem(context, id)
                    val newHash = contact?.dataHash() ?: ""
                    ops.add(ContentProviderOperation.newUpdate(syncAdapterUri(ContactsContract.RawContacts.CONTENT_URI))
                        .withValue(ContactsContract.RawContacts.DIRTY, 0)
                        .withValue(ContactsContract.RawContacts.SYNC1, fileName)
                        .withValue(ContactsContract.RawContacts.SYNC2, newHash)
                        .withSelection("${ContactsContract.RawContacts._ID}=?", arrayOf(id.toString()))
                        .build())
                    SyncLogger.log(context, "contacts", "Saved to file: $fileName")
                    if (ops.size >= 100) {
                        try { resolver.applyBatch(ContactsContract.AUTHORITY, ops) } catch (e: Exception) { }
                        ops.clear()
                    }
                }
            }
        }
        if (ops.isNotEmpty()) {
            try { resolver.applyBatch(ContactsContract.AUTHORITY, ops) } catch (e: Exception) { }
        }
    }

    /**
     * Generates a deterministic, collision-free filename for a new contact.
     * Uses sanitized display name; appends (2), (3), etc. only when a collision
     * with a *different* contact is detected.
     */
    private fun generateFileName(folder: DocumentFile, displayName: String): String {
        val sanitized = displayName.replace(Regex("[^a-zA-Z0-9 ]"), "").trim().replace(Regex("\\s+"), "_")
        val base = sanitized.ifEmpty { "Unnamed" }
        var candidate = "$base.vcf"
        var counter = 2
        while (folder.findFile(candidate) != null) {
            candidate = "$base ($counter).vcf"
            counter++
        }
        return candidate
    }

    // ── System deletions ─────────────────────────────────────────────────

    private fun handleSystemDeletions(context: Context, folderUri: Uri) {
        val resolver = context.contentResolver
        val folder = DocumentFile.fromTreeUri(context, folderUri) ?: return

        val selection = "${ContactsContract.RawContacts.ACCOUNT_TYPE}=? AND ${ContactsContract.RawContacts.DELETED}=1"
        val ops = ArrayList<ContentProviderOperation>()

        resolver.query(
            syncAdapterUri(ContactsContract.RawContacts.CONTENT_URI),
            arrayOf(ContactsContract.RawContacts._ID, ContactsContract.RawContacts.SYNC1),
            selection, arrayOf(ACCOUNT_TYPE), null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val fileName = cursor.getString(1)
                if (fileName != null) {
                    folder.findFile(fileName)?.delete()
                    SyncLogger.log(context, "contacts", "Deleted file for removed contact: $fileName")
                }
                ops.add(ContentProviderOperation.newDelete(syncAdapterUri(ContactsContract.RawContacts.CONTENT_URI))
                    .withSelection("${ContactsContract.RawContacts._ID}=?", arrayOf(id.toString()))
                    .build())
                if (ops.size >= 100) {
                    try { resolver.applyBatch(ContactsContract.AUTHORITY, ops) } catch (e: Exception) { }
                    ops.clear()
                }
            }
        }
        if (ops.isNotEmpty()) {
            try { resolver.applyBatch(ContactsContract.AUTHORITY, ops) } catch (e: Exception) { }
        }
    }

    // ── Export contact to VCF ────────────────────────────────────────────

    private fun exportContactToFile(context: Context, rawId: Long, folder: DocumentFile, fileName: String): Boolean {
        try {
            val contact = fetchContactFromSystem(context, rawId) ?: return false
            val vcardString = convertContactToVcard(contact)
            val file = folder.findFile(fileName) ?: folder.createFile("text/vcard", fileName)
            file?.let {
                context.contentResolver.openOutputStream(it.uri, "wt")?.use { out ->
                    out.write(vcardString.toByteArray())
                }
                return true
            }
        } catch (e: Exception) {
            SyncLogger.log(context, "contacts", "Export error for $fileName: ${e.message}")
        }
        return false
    }

    // ── Read contact from Android provider ───────────────────────────────

    private fun fetchContactFromSystem(context: Context, rawId: Long): Contact? {
        val resolver = context.contentResolver
        var firstName = ""; var lastName = ""; var displayName = ""
        val phones = mutableListOf<TypedValue>()
        val emails = mutableListOf<TypedValue>()
        val addresses = mutableListOf<TypedAddress>()
        var note = ""; var org = ""; var birthday: String? = null
        val websites = mutableListOf<String>()

        resolver.query(
            ContactsContract.Data.CONTENT_URI, null,
            "${ContactsContract.Data.RAW_CONTACT_ID}=?", arrayOf(rawId.toString()), null
        )?.use { cursor ->
            val mimeIdx = cursor.getColumnIndexOrThrow(ContactsContract.Data.MIMETYPE)
            while (cursor.moveToNext()) {
                when (cursor.getString(mimeIdx)) {
                    ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE -> {
                        firstName = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME)) ?: ""
                        lastName = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME)) ?: ""
                        displayName = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME)) ?: ""
                    }
                    ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE -> {
                        val number = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)) ?: ""
                        val type = cursor.getInt(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.TYPE))
                        if (number.isNotBlank()) phones.add(TypedValue(number, type))
                    }
                    ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE -> {
                        val addr = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Email.ADDRESS)) ?: ""
                        val type = cursor.getInt(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Email.TYPE))
                        if (addr.isNotBlank()) emails.add(TypedValue(addr, type))
                    }
                    ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE -> {
                        note = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Note.NOTE)) ?: ""
                    }
                    ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE -> {
                        org = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Organization.COMPANY)) ?: ""
                    }
                    ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE -> {
                        addresses.add(TypedAddress(
                            street = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.StructuredPostal.STREET)) ?: "",
                            city = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.StructuredPostal.CITY)) ?: "",
                            region = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.StructuredPostal.REGION)) ?: "",
                            postcode = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.StructuredPostal.POSTCODE)) ?: "",
                            country = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.StructuredPostal.COUNTRY)) ?: "",
                            type = cursor.getInt(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.StructuredPostal.TYPE))
                        ))
                    }
                    ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE -> {
                        val eventType = cursor.getInt(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Event.TYPE))
                        if (eventType == ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY) {
                            birthday = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Event.START_DATE))
                        }
                    }
                    ContactsContract.CommonDataKinds.Website.CONTENT_ITEM_TYPE -> {
                        cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Website.URL))?.let { websites.add(it) }
                    }
                }
            }
        }

        return if (displayName.isNotBlank()) {
            Contact(id = rawId.toString(), firstName = firstName, lastName = lastName,
                displayName = displayName, phoneNumbers = phones, emails = emails,
                addresses = addresses, organization = org, note = note,
                birthday = birthday, websites = websites)
        } else null
    }

    // ── Convert Contact → VCard string ───────────────────────────────────

    private fun convertContactToVcard(contact: Contact): String {
        val vcard = ezvcard.VCard()

        // Name
        val n = ezvcard.property.StructuredName()
        n.family = contact.lastName
        n.given = contact.firstName
        vcard.structuredName = n
        vcard.formattedName = ezvcard.property.FormattedName(contact.displayName)

        // Organization
        if (contact.organization.isNotBlank()) {
            val org = ezvcard.property.Organization()
            org.values.add(contact.organization)
            vcard.addOrganization(org)
        }

        // Phones with type
        contact.phoneNumbers.forEach { tv ->
            val tel = ezvcard.property.Telephone(tv.value)
            tel.types.add(androidPhoneTypeToVcard(tv.type))
            vcard.addTelephoneNumber(tel)
        }

        // Emails with type
        contact.emails.forEach { tv ->
            val email = ezvcard.property.Email(tv.value)
            email.types.add(androidEmailTypeToVcard(tv.type))
            vcard.addEmail(email)
        }

        // Addresses
        contact.addresses.forEach { addr ->
            val a = ezvcard.property.Address()
            a.streetAddress = addr.street
            a.locality = addr.city
            a.region = addr.region
            a.postalCode = addr.postcode
            a.country = addr.country
            a.types.add(androidAddressTypeToVcard(addr.type))
            vcard.addAddress(a)
        }

        // Note
        if (contact.note.isNotBlank()) {
            vcard.addNote(contact.note)
        }

        // Birthday
        contact.birthday?.let { bday ->
            try {
                val parts = bday.split("-")
                if (parts.size == 3) {
                    val localDate = java.time.LocalDate.of(parts[0].toInt(), parts[1].toInt(), parts[2].toInt())
                    vcard.birthday = ezvcard.property.Birthday(localDate)
                }
            } catch (_: Exception) { }
        }

        // Websites
        contact.websites.forEach { url ->
            vcard.addUrl(url)
        }

        return ezvcard.Ezvcard.write(vcard).version(ezvcard.VCardVersion.V3_0).go()
    }

    // ── Type mapping helpers ─────────────────────────────────────────────

    private fun androidPhoneTypeToVcard(type: Int): TelephoneType = when (type) {
        ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE -> TelephoneType.CELL
        ContactsContract.CommonDataKinds.Phone.TYPE_HOME -> TelephoneType.HOME
        ContactsContract.CommonDataKinds.Phone.TYPE_WORK -> TelephoneType.WORK
        ContactsContract.CommonDataKinds.Phone.TYPE_FAX_WORK -> TelephoneType.FAX
        ContactsContract.CommonDataKinds.Phone.TYPE_FAX_HOME -> TelephoneType.FAX
        ContactsContract.CommonDataKinds.Phone.TYPE_PAGER -> TelephoneType.PAGER
        else -> TelephoneType.VOICE
    }

    private fun androidEmailTypeToVcard(type: Int): EmailType = when (type) {
        ContactsContract.CommonDataKinds.Email.TYPE_HOME -> EmailType.HOME
        ContactsContract.CommonDataKinds.Email.TYPE_WORK -> EmailType.WORK
        else -> EmailType.HOME
    }

    private fun androidAddressTypeToVcard(type: Int): AddressType = when (type) {
        ContactsContract.CommonDataKinds.StructuredPostal.TYPE_HOME -> AddressType.HOME
        ContactsContract.CommonDataKinds.StructuredPostal.TYPE_WORK -> AddressType.WORK
        else -> AddressType.HOME
    }

    // ── Data hash for change detection ───────────────────────────────────

    private fun Contact.dataHash(): String {
        val dataStr = buildString {
            append("$firstName|$lastName|$displayName|$organization|$note|$birthday|")
            append(phoneNumbers.sortedBy { it.value }.joinToString(",") { "${it.value}:${it.type}" })
            append("|")
            append(emails.sortedBy { it.value }.joinToString(",") { "${it.value}:${it.type}" })
            append("|")
            append(addresses.sortedBy { it.formatted() }.joinToString(",") { "${it.formatted()}:${it.type}" })
            append("|")
            append(websites.sorted().joinToString(","))
        }
        val bytes = MessageDigest.getInstance("MD5").digest(dataStr.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    // ── Insert / Update operations ───────────────────────────────────────

    private fun addInsertContactOps(ops: ArrayList<ContentProviderOperation>, contact: Contact, fileName: String, groupId: Long?, hash: String) {
        val rawContactInsertIndex = ops.size
        ops.add(ContentProviderOperation.newInsert(syncAdapterUri(ContactsContract.RawContacts.CONTENT_URI))
            .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, ACCOUNT_TYPE)
            .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, ACCOUNT_NAME)
            .withValue(ContactsContract.RawContacts.AGGREGATION_MODE, ContactsContract.RawContacts.AGGREGATION_MODE_DISABLED)
            .withValue(ContactsContract.RawContacts.SYNC1, fileName)
            .withValue(ContactsContract.RawContacts.SYNC2, hash)
            .build())

        addContactDataOps(ops, contact, null, groupId, rawContactInsertIndex)
    }

    private fun addUpdateContactOps(ops: ArrayList<ContentProviderOperation>, contact: Contact, rawId: Long, groupId: Long?, hash: String) {
        ops.add(ContentProviderOperation.newUpdate(syncAdapterUri(ContactsContract.RawContacts.CONTENT_URI))
            .withSelection("${ContactsContract.RawContacts._ID}=?", arrayOf(rawId.toString()))
            .withValue(ContactsContract.RawContacts.SYNC2, hash)
            .build())

        // Delete only the MIME types we manage — photos, custom fields, etc. survive
        val mimeSelection = "${ContactsContract.Data.RAW_CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE} IN (${MANAGED_MIMETYPES.joinToString(",") { "?" }})"
        val mimeArgs = arrayOf(rawId.toString()) + MANAGED_MIMETYPES
        ops.add(ContentProviderOperation.newDelete(syncAdapterUri(ContactsContract.Data.CONTENT_URI))
            .withSelection(mimeSelection, mimeArgs)
            .build())

        addContactDataOps(ops, contact, rawId, groupId, -1)
    }

    private fun addContactDataOps(ops: ArrayList<ContentProviderOperation>, contact: Contact, rawId: Long?, groupId: Long?, rawContactInsertIndex: Int) {
        fun op() = ContentProviderOperation.newInsert(syncAdapterUri(ContactsContract.Data.CONTENT_URI)).apply {
            if (rawId != null) withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId)
            else withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawContactInsertIndex)
        }

        // StructuredName
        ops.add(op()
            .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
            .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, contact.displayName)
            .withValue(ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME, contact.firstName)
            .withValue(ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME, contact.lastName)
            .build())

        // Phones (with type)
        contact.phoneNumbers.forEach { tv ->
            ops.add(op()
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, tv.value)
                .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, tv.type)
                .build())
        }

        // Emails (with type)
        contact.emails.forEach { tv ->
            ops.add(op()
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, tv.value)
                .withValue(ContactsContract.CommonDataKinds.Email.TYPE, tv.type)
                .build())
        }

        // Organization
        if (contact.organization.isNotBlank()) {
            ops.add(op()
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Organization.COMPANY, contact.organization)
                .build())
        }

        // Note
        if (contact.note.isNotBlank()) {
            ops.add(op()
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Note.NOTE, contact.note)
                .build())
        }

        // Addresses
        contact.addresses.forEach { addr ->
            ops.add(op()
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.StructuredPostal.STREET, addr.street)
                .withValue(ContactsContract.CommonDataKinds.StructuredPostal.CITY, addr.city)
                .withValue(ContactsContract.CommonDataKinds.StructuredPostal.REGION, addr.region)
                .withValue(ContactsContract.CommonDataKinds.StructuredPostal.POSTCODE, addr.postcode)
                .withValue(ContactsContract.CommonDataKinds.StructuredPostal.COUNTRY, addr.country)
                .withValue(ContactsContract.CommonDataKinds.StructuredPostal.TYPE, addr.type)
                .build())
        }

        // Birthday
        contact.birthday?.let { bday ->
            ops.add(op()
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Event.START_DATE, bday)
                .withValue(ContactsContract.CommonDataKinds.Event.TYPE, ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY)
                .build())
        }

        // Websites
        contact.websites.forEach { url ->
            ops.add(op()
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Website.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Website.URL, url)
                .build())
        }

        // Group membership
        if (groupId != null) {
            ops.add(op()
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID, groupId)
                .build())
        }
    }

    // ── Account & group management ───────────────────────────────────────

    private fun syncAdapterUri(uri: Uri): Uri {
        return uri.buildUpon()
            .appendQueryParameter(ContactsContract.RawContacts.ACCOUNT_NAME, ACCOUNT_NAME)
            .appendQueryParameter(ContactsContract.RawContacts.ACCOUNT_TYPE, ACCOUNT_TYPE)
            .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
            .build()
    }

    fun ensureAccountExists(context: Context) {
        val am = AccountManager.get(context)
        val accounts = am.getAccountsByType(ACCOUNT_TYPE)
        if (accounts.isEmpty()) {
            val account = Account(ACCOUNT_NAME, ACCOUNT_TYPE)
            am.addAccountExplicitly(account, null, null)
            ContentResolver.setIsSyncable(account, ContactsContract.AUTHORITY, 1)
            ContentResolver.setSyncAutomatically(account, ContactsContract.AUTHORITY, true)

            val values = ContentValues()
            values.put(ContactsContract.Settings.ACCOUNT_NAME, ACCOUNT_NAME)
            values.put(ContactsContract.Settings.ACCOUNT_TYPE, ACCOUNT_TYPE)
            values.put(ContactsContract.Settings.UNGROUPED_VISIBLE, 1)
            values.put(ContactsContract.Settings.SHOULD_SYNC, 1)
            context.contentResolver.insert(ContactsContract.Settings.CONTENT_URI, values)
        }
    }

    private fun ensureDefaultGroupExists(context: Context): Long? {
        val selection = "${ContactsContract.Groups.ACCOUNT_NAME}=? AND ${ContactsContract.Groups.ACCOUNT_TYPE}=? AND ${ContactsContract.Groups.TITLE}=?"
        val selectionArgs = arrayOf(ACCOUNT_NAME, ACCOUNT_TYPE, "My Contacts")

        context.contentResolver.query(ContactsContract.Groups.CONTENT_URI, arrayOf(ContactsContract.Groups._ID), selection, selectionArgs, null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getLong(0)
        }

        val values = ContentValues()
        values.put(ContactsContract.Groups.ACCOUNT_NAME, ACCOUNT_NAME)
        values.put(ContactsContract.Groups.ACCOUNT_TYPE, ACCOUNT_TYPE)
        values.put(ContactsContract.Groups.TITLE, "My Contacts")
        values.put(ContactsContract.Groups.GROUP_VISIBLE, 1)
        values.put(ContactsContract.Groups.SHOULD_SYNC, 1)
        values.put(ContactsContract.Groups.SYSTEM_ID, "Contacts")

        return try {
            val uri = context.contentResolver.insert(syncAdapterUri(ContactsContract.Groups.CONTENT_URI), values)
            ContentUris.parseId(uri!!)
        } catch (e: Exception) { null }
    }

    fun deleteAllSystemContacts(context: Context) {
        context.contentResolver.delete(syncAdapterUri(ContactsContract.RawContacts.CONTENT_URI), "${ContactsContract.RawContacts.ACCOUNT_TYPE}=?", arrayOf(ACCOUNT_TYPE))
    }

    private fun getSystemContacts(context: Context): Map<String, Pair<Long, String?>> {
        val map = mutableMapOf<String, Pair<Long, String?>>()
        context.contentResolver.query(
            syncAdapterUri(ContactsContract.RawContacts.CONTENT_URI),
            arrayOf(ContactsContract.RawContacts._ID, ContactsContract.RawContacts.SYNC1, ContactsContract.RawContacts.SYNC2),
            "${ContactsContract.RawContacts.ACCOUNT_TYPE}=? AND ${ContactsContract.RawContacts.DELETED}=0",
            arrayOf(ACCOUNT_TYPE), null
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(ContactsContract.RawContacts._ID)
            val fileIdx = cursor.getColumnIndexOrThrow(ContactsContract.RawContacts.SYNC1)
            val hashIdx = cursor.getColumnIndexOrThrow(ContactsContract.RawContacts.SYNC2)
            while (cursor.moveToNext()) {
                val name = cursor.getString(fileIdx)
                val hash = if (hashIdx >= 0) cursor.getString(hashIdx) else null
                if (name != null) map[name] = Pair(cursor.getLong(idIdx), hash)
            }
        }
        return map
    }

    // ── File health repair pass ──────────────────────────────────────────────

    /**
     * Post-sync repair pass. Runs after the main sync loop for each contact folder.
     *
     * - Malformed VCF (can't parse) with a known SYNC1→rawId mapping → reconstructed from provider
     * - Clean VCF with a Legacy UUID suffix (Name_a3f7b21c.vcf) → renamed to Name.vcf and SYNC1 updated
     * - Multiple files resolving to the same clean name → logged as a warning (user should merge manually)
     *
     * Never deletes files.
     */
    private fun performRepairPass(context: Context, folderUri: Uri) {
        val folder = DocumentFile.fromTreeUri(context, folderUri) ?: return
        SyncLogger.log(context, "contacts", "Contact repair pass starting...")

        // Build a reverse map: filename → rawContactId (from SYNC1)
        val sync1Map = mutableMapOf<String, Long>()   // filename → rawContactId
        context.contentResolver.query(
            syncAdapterUri(ContactsContract.RawContacts.CONTENT_URI),
            arrayOf(ContactsContract.RawContacts._ID, ContactsContract.RawContacts.SYNC1),
            "${ContactsContract.RawContacts.ACCOUNT_TYPE}=? AND ${ContactsContract.RawContacts.DELETED}=0",
            arrayOf(ACCOUNT_TYPE), null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val name = cursor.getString(1)
                if (name != null) sync1Map[name] = id
            }
        }

        val vcfFiles = folder.listFiles().filter { it.name?.endsWith(".vcf", ignoreCase = true) == true }

        // Track clean-name → file count to detect duplicates
        val cleanNameCount = mutableMapOf<String, Int>()

        vcfFiles.forEach { file ->
            val fileName = file.name ?: return@forEach

            // ── 1. Try to parse ─────────────────────────────────────────────
            val contacts = VCardParser.parseVCardFromUri(context, file.uri)
            val contact = contacts.firstOrNull()

            if (contact == null || contact.displayName.isBlank()) {
                // Malformed / empty VCF
                val rawId = sync1Map[fileName] ?: run {
                    SyncLogger.log(context, "contacts", "Repair: malformed VCF with no SYNC1 mapping, skipping: $fileName")
                    return@forEach
                }
                val systemContact = fetchContactFromSystem(context, rawId) ?: run {
                    SyncLogger.log(context, "contacts", "Repair: no provider entry for malformed VCF (id=$rawId), skipping: $fileName")
                    return@forEach
                }
                try {
                    val vcardString = convertContactToVcard(systemContact)
                    context.contentResolver.openOutputStream(file.uri, "wt")?.use {
                        it.write(vcardString.toByteArray())
                    }
                    SyncLogger.log(context, "contacts", "Repair: reconstructed malformed VCF: $fileName")
                } catch (e: Exception) {
                    SyncLogger.log(context, "contacts", "Repair: failed to reconstruct $fileName: ${e.message}")
                }
                return@forEach
            }

            // ── 2. Check and fix filename ────────────────────────────────────
            val expectedBase = contact.displayName
                .replace(Regex("[^a-zA-Z0-9 ]"), "").trim().replace(Regex("\\s+"), "_")
                .ifEmpty { "Unnamed" }
            val expectedName = "$expectedBase.vcf"

            // Detect legacy UUID suffix: Name_XXXXXXXX.vcf where XXXXXXXX is an 8-char hex string
            val hasLegacySuffix = Regex("""^(.+)_[0-9a-f]{8}\.vcf$""", RegexOption.IGNORE_CASE).matches(fileName)

            if (fileName != expectedName && hasLegacySuffix) {
                // Check for collision at the target name
                val existingAtTarget = folder.findFile(expectedName)
                if (existingAtTarget != null && existingAtTarget.uri != file.uri) {
                    SyncLogger.log(context, "contacts", "Repair: target '$expectedName' already occupied, skipping rename of $fileName")
                    return@forEach
                }

                try {
                    file.renameTo(expectedName)
                    // Update SYNC1 in the provider
                    val rawId = sync1Map[fileName]
                    if (rawId != null) {
                        val ops = arrayListOf(
                            ContentProviderOperation.newUpdate(syncAdapterUri(ContactsContract.RawContacts.CONTENT_URI))
                                .withSelection("${ContactsContract.RawContacts._ID}=?", arrayOf(rawId.toString()))
                                .withValue(ContactsContract.RawContacts.SYNC1, expectedName)
                                .build()
                        )
                        context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
                    }
                    SyncLogger.log(context, "contacts", "Repair: renamed $fileName → $expectedName")
                } catch (e: Exception) {
                    SyncLogger.log(context, "contacts", "Repair: rename failed for $fileName: ${e.message}")
                }
            }

            // ── 3. Detect duplicate display names ───────────────────────────
            cleanNameCount[expectedBase] = (cleanNameCount[expectedBase] ?: 0) + 1
        }

        cleanNameCount.filter { it.value > 1 }.forEach { (name, count) ->
            SyncLogger.log(context, "contacts", "Repair: WARNING — $count VCF files map to the same contact name '$name'. Consider merging them manually.")
        }

        SyncLogger.log(context, "contacts", "Contact repair pass complete.")
    }
}
