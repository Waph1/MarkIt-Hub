package com.waph1.markithub.util

import android.content.Context
import android.provider.ContactsContract
import android.util.Log

object DebugDeletions {
    private const val ACCOUNT_TYPE = "com.waph1.markithub.contacts"

    fun printDeletions(context: Context) {
        val resolver = context.contentResolver
        val selection = "${ContactsContract.RawContacts.ACCOUNT_TYPE}=? AND ${ContactsContract.RawContacts.DELETED}=1"
        val selectionArgs = arrayOf(ACCOUNT_TYPE)

        resolver.query(
            ContactsContract.RawContacts.CONTENT_URI.buildUpon()
                .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true").build(),
            arrayOf(ContactsContract.RawContacts._ID, ContactsContract.RawContacts.SYNC1, ContactsContract.RawContacts.DELETED),
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
             while (cursor.moveToNext()) {
                  val id = cursor.getLong(0)
                  val sync1 = cursor.getString(1)
                  val deleted = cursor.getInt(2)
                  Log.d("SYNC_DEBUG", "Found DELETED=1 -> ID: $id, SYNC1: $sync1, DELETED: $deleted")
             }
        }
        
        // Also check reverseSync logic
        val sel2 = "${ContactsContract.RawContacts.ACCOUNT_TYPE}=? AND (${ContactsContract.RawContacts.DIRTY}=1 OR ${ContactsContract.RawContacts.SYNC1} IS NULL) AND ${ContactsContract.RawContacts.DELETED}=0"
        resolver.query(
            ContactsContract.RawContacts.CONTENT_URI.buildUpon()
                .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true").build(),
            arrayOf(ContactsContract.RawContacts._ID, ContactsContract.RawContacts.SYNC1, ContactsContract.RawContacts.DIRTY),
            sel2,
            selectionArgs,
            null
        )?.use { cursor ->
             while (cursor.moveToNext()) {
                  val id = cursor.getLong(0)
                  val sync1 = cursor.getString(1)
                  val dirty = cursor.getInt(2)
                  Log.d("SYNC_DEBUG", "Found DIRTY/NEW -> ID: $id, SYNC1: $sync1, DIRTY: $dirty")
             }
        }
    }
}
