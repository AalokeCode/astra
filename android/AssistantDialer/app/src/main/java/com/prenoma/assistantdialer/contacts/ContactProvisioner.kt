package com.prenoma.assistantdialer.contacts

import android.content.ContentProviderOperation
import android.content.Context
import android.provider.ContactsContract
import android.telephony.PhoneNumberUtils

object ContactProvisioner {
    fun createAssistantContact(context: Context, number: String): Boolean {
        if (exists(context, number)) return false

        val rawContactInsertIndex = 0
        val operations = arrayListOf(
            ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                .build(),
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawContactInsertIndex)
                .withValue(
                    ContactsContract.Data.MIMETYPE,
                    ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
                )
                .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, CONTACT_NAME)
                .build(),
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawContactInsertIndex)
                .withValue(
                    ContactsContract.Data.MIMETYPE,
                    ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
                )
                .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, number)
                .withValue(
                    ContactsContract.CommonDataKinds.Phone.TYPE,
                    ContactsContract.CommonDataKinds.Phone.TYPE_OTHER,
                )
                .build(),
        )
        context.contentResolver.applyBatch(ContactsContract.AUTHORITY, operations)
        return true
    }

    private fun exists(context: Context, number: String): Boolean {
        val normalizedTarget = PhoneNumberUtils.normalizeNumber(number)
        val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER)
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY} = ?",
            arrayOf(CONTACT_NAME),
            null,
        )?.use { cursor ->
            val numberColumn = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (cursor.moveToNext()) {
                if (PhoneNumberUtils.normalizeNumber(cursor.getString(numberColumn)) == normalizedTarget) {
                    return true
                }
            }
        }
        return false
    }

    private const val CONTACT_NAME = "ASTRA"
}
