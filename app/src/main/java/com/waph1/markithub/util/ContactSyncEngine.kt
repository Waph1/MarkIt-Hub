package com.waph1.markithub.util

import android.accounts.Account
import android.accounts.AccountManager
import android.content.*
import android.net.Uri
import android.provider.ContactsContract
import androidx.documentfile.provider.DocumentFile
import com.waph1.markithub.model.Contact
import java.security.MessageDigest
import java.util.ArrayList

object ContactSyncEngine {
    private const val ACCOUNT_TYPE = "com.waph1.markithub.contacts"
    private const val ACCOUNT_NAME = "MarkItHub Contacts"

    fun sync(context: Context, folderUri: Uri) {
        ensureAccountExists(context)
        val groupId = ensureDefaultGroupExists(context)
        
        SyncLogger.log(context, "contacts", "Syncing contacts (DecSync style)...")
        val folder = DocumentFile.fromTreeUri(context, folderUri)
        if (folder == null || !folder.isDirectory) {
            SyncLogger.log(context, "contacts", "Sync failed: folder invalid")
            return
        }

        val resolver = context.contentResolver
        val systemContacts = getSystemContacts(context)
        // 5. Reverse Sync: System -> File First (Commit local changes)
        reverseSync(context, folderUri)
        
        // 6. Handle System Deletions: System (Deleted) -> File (Delete)
        handleSystemDeletions(context, folderUri)

        val vcfFiles = folder.listFiles().filter { it.name?.endsWith(".vcf", ignoreCase = true) == true }
        val filesFound = vcfFiles.mapNotNull { it.name }.toSet()

        val ops = ArrayList<ContentProviderOperation>()
        fun applyOps() {
            if (ops.size >= 100) {
                try {
                    context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
                } catch (e: Exception) {
                    SyncLogger.log(context, "contacts", "Batch error: ${e.message}")
                }
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
                // Skip if hashes match
                applyOps()
            }
        }

        systemContacts.forEach { (fileName, rawData) ->
            if (fileName !in filesFound) {
                ops.add(ContentProviderOperation.newDelete(syncAdapterUri(ContactsContract.RawContacts.CONTENT_URI))
                    .withSelection("${ContactsContract.RawContacts._ID}=?", arrayOf(rawData.first.toString()))
                    .build())
                SyncLogger.log(context, "contacts", "Removed: $fileName")
                applyOps()
            }
        }

        if (ops.isNotEmpty()) {
            try {
                context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            } catch (e: Exception) {
                SyncLogger.log(context, "contacts", "Final batch error: ${e.message}")
            }
        }

        SyncLogger.log(context, "contacts", "Sync complete.")
    }

    private fun reverseSync(context: Context, folderUri: Uri) {
        val resolver = context.contentResolver
        val folder = DocumentFile.fromTreeUri(context, folderUri) ?: return
        
        // DIRTY=1 means modified in UI. SYNC1 IS NULL means newly created in UI.
        val selection = "${ContactsContract.RawContacts.ACCOUNT_TYPE}=? AND (${ContactsContract.RawContacts.DIRTY}=1 OR ${ContactsContract.RawContacts.SYNC1} IS NULL) AND ${ContactsContract.RawContacts.DELETED}=0"
        val selectionArgs = arrayOf(ACCOUNT_TYPE)
        
        val ops = ArrayList<ContentProviderOperation>()
        resolver.query(
            syncAdapterUri(ContactsContract.RawContacts.CONTENT_URI),
            arrayOf(ContactsContract.RawContacts._ID, ContactsContract.RawContacts.SYNC1, ContactsContract.RawContacts.DISPLAY_NAME_PRIMARY),
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val existingFileName = cursor.getString(1)
                val displayName = cursor.getString(2) ?: "Unnamed"
                
                // If it's a new contact, generate a unique but readable filename
                val fileName = existingFileName ?: run {
                    val sanitized = displayName.replace(Regex("[^a-zA-Z0-9]"), "_")
                    val shortId = java.util.UUID.randomUUID().toString().substring(0, 8)
                    "${sanitized}_$shortId.vcf"
                }
                
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

    private fun handleSystemDeletions(context: Context, folderUri: Uri) {
        val resolver = context.contentResolver
        val folder = DocumentFile.fromTreeUri(context, folderUri) ?: return
        
        // Android marks contacts as DELETED=1 instead of removing them immediately if they are in a sync account
        val selection = "${ContactsContract.RawContacts.ACCOUNT_TYPE}=? AND ${ContactsContract.RawContacts.DELETED}=1"
        val selectionArgs = arrayOf(ACCOUNT_TYPE)
        
        val ops = ArrayList<ContentProviderOperation>()
        resolver.query(
            syncAdapterUri(ContactsContract.RawContacts.CONTENT_URI),
            arrayOf(ContactsContract.RawContacts._ID, ContactsContract.RawContacts.SYNC1),
            selection,
            selectionArgs,
            null
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

    private fun fetchContactFromSystem(context: Context, rawId: Long): Contact? {
        val resolver = context.contentResolver
        var firstName = ""
        var lastName = ""
        var displayName = ""
        val phones = mutableListOf<String>()
        val emails = mutableListOf<String>()
        var note = ""
        var org = ""

        val cursor = resolver.query(
            ContactsContract.Data.CONTENT_URI,
            null,
            "${ContactsContract.Data.RAW_CONTACT_ID}=?",
            arrayOf(rawId.toString()),
            null
        )

        cursor?.use {
            val mimeIdx = it.getColumnIndex(ContactsContract.Data.MIMETYPE)
            while (it.moveToNext()) {
                when (it.getString(mimeIdx)) {
                    ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE -> {
                        firstName = it.getString(it.getColumnIndex(ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME)) ?: ""
                        lastName = it.getString(it.getColumnIndex(ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME)) ?: ""
                        displayName = it.getString(it.getColumnIndex(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME)) ?: ""
                    }
                    ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE -> {
                        it.getString(it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER))?.let { p -> phones.add(p) }
                    }
                    ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE -> {
                        it.getString(it.getColumnIndex(ContactsContract.CommonDataKinds.Email.ADDRESS))?.let { e -> emails.add(e) }
                    }
                    ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE -> {
                        note = it.getString(it.getColumnIndex(ContactsContract.CommonDataKinds.Note.NOTE)) ?: ""
                    }
                    ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE -> {
                        org = it.getString(it.getColumnIndex(ContactsContract.CommonDataKinds.Organization.COMPANY)) ?: ""
                    }
                }
            }
        }

        return if (displayName.isNotBlank()) {
            Contact(id = rawId.toString(), firstName = firstName, lastName = lastName, displayName = displayName, phoneNumbers = phones, emails = emails, organization = org, note = note)
        } else null
    }

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
        
        // Phones
        contact.phoneNumbers.forEach { 
            vcard.addTelephoneNumber(it) 
        }
        
        // Emails
        contact.emails.forEach { 
            vcard.addEmail(it) 
        }
        
        // Note
        if (contact.note.isNotBlank()) {
            vcard.addNote(contact.note)
        }
        
        return ezvcard.Ezvcard.write(vcard).version(ezvcard.VCardVersion.V3_0).go()
    }

    private fun Contact.dataHash(): String {
        val dataStr = "$firstName|$lastName|$displayName|${phoneNumbers.sorted().joinToString()}|${emails.sorted().joinToString()}|$organization|$note"
        val bytes = MessageDigest.getInstance("MD5").digest(dataStr.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

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
            
            // Critical settings for Samsung
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
        values.put(ContactsContract.Groups.SYSTEM_ID, "Contacts") // Special ID for "All Contacts"
        
        return try {
            val uri = context.contentResolver.insert(syncAdapterUri(ContactsContract.Groups.CONTENT_URI), values)
            ContentUris.parseId(uri!!)
        } catch (e: Exception) {
            null
        }
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
            arrayOf(ACCOUNT_TYPE),
            null
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndex(ContactsContract.RawContacts._ID)
            val fileIdx = cursor.getColumnIndex(ContactsContract.RawContacts.SYNC1)
            val hashIdx = cursor.getColumnIndex(ContactsContract.RawContacts.SYNC2)
            while (cursor.moveToNext()) {
                val name = cursor.getString(fileIdx)
                val hash = if (hashIdx >= 0) cursor.getString(hashIdx) else null
                if (name != null) map[name] = Pair(cursor.getLong(idIdx), hash)
            }
        }
        return map
    }

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

        ops.add(ContentProviderOperation.newDelete(syncAdapterUri(ContactsContract.Data.CONTENT_URI))
            .withSelection("${ContactsContract.Data.RAW_CONTACT_ID}=?", arrayOf(rawId.toString()))
            .build())

        addContactDataOps(ops, contact, rawId, groupId, -1)
    }

    private fun addContactDataOps(ops: ArrayList<ContentProviderOperation>, contact: Contact, rawId: Long?, groupId: Long?, rawContactInsertIndex: Int) {
        fun op() = ContentProviderOperation.newInsert(syncAdapterUri(ContactsContract.Data.CONTENT_URI)).apply {
            if (rawId != null) withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId)
            else withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawContactInsertIndex)
        }

        ops.add(op()
            .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
            .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, contact.displayName)
            .withValue(ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME, contact.firstName)
            .withValue(ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME, contact.lastName)
            .build())

        contact.phoneNumbers.forEach { phone ->
            ops.add(op()
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phone)
                .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                .build())
        }

        contact.emails.forEach { email ->
            ops.add(op()
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, email)
                .withValue(ContactsContract.CommonDataKinds.Email.TYPE, ContactsContract.CommonDataKinds.Email.TYPE_WORK)
                .build())
        }

        if (groupId != null) {
            ops.add(op()
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID, groupId)
                .build())
        }
    }
}
