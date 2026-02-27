package com.waph1.markithub.util

import android.content.Context
import android.accounts.Account
import android.content.ContentResolver
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.ContactsContract

object SyncScheduler {
    private const val ACCOUNT_NAME_CALENDAR = "MarkItHub Calendars"
    private const val ACCOUNT_TYPE_CALENDAR = "com.waph1.markithub.calendars"
    private const val ACCOUNT_NAME_CONTACTS = "MarkItHub Contacts"
    private const val ACCOUNT_TYPE_CONTACTS = "com.waph1.markithub.contacts"

    fun schedule(context: Context, minutes: Long) {
        val account = Account(ACCOUNT_NAME_CALENDAR, ACCOUNT_TYPE_CALENDAR)
        val authority = CalendarContract.AUTHORITY

        if (minutes > 0) {
            val seconds = minutes * 60
            ContentResolver.addPeriodicSync(account, authority, Bundle.EMPTY, seconds)
            SyncLogger.log(context, "calendar", "System periodic calendar sync scheduled every $minutes minutes.")
        } else {
            ContentResolver.removePeriodicSync(account, authority, Bundle.EMPTY)
            SyncLogger.log(context, "calendar", "System periodic calendar sync disabled.")
        }
    }

    fun scheduleContactsSync(context: Context, minutes: Long) {
        val account = Account(ACCOUNT_NAME_CONTACTS, ACCOUNT_TYPE_CONTACTS)
        val authority = ContactsContract.AUTHORITY

        if (minutes > 0) {
            val seconds = minutes * 60
            ContentResolver.addPeriodicSync(account, authority, Bundle.EMPTY, seconds)
            SyncLogger.log(context, "contacts", "System periodic contacts sync scheduled every $minutes minutes.")
        } else {
            ContentResolver.removePeriodicSync(account, authority, Bundle.EMPTY)
            SyncLogger.log(context, "contacts", "System periodic contacts sync disabled.")
        }
    }

    fun stopAll(context: Context) {
        val calendarAccount = Account(ACCOUNT_NAME_CALENDAR, ACCOUNT_TYPE_CALENDAR)
        val contactsAccount = Account(ACCOUNT_NAME_CONTACTS, ACCOUNT_TYPE_CONTACTS)
        
        ContentResolver.removePeriodicSync(calendarAccount, CalendarContract.AUTHORITY, Bundle.EMPTY)
        ContentResolver.removePeriodicSync(contactsAccount, ContactsContract.AUTHORITY, Bundle.EMPTY)
        
        SyncLogger.log(context, "calendar", "All system synchronization tasks stopped.")
    }
}
