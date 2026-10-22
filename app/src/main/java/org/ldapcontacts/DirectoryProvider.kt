package org.ldapcontacts

import android.accounts.AccountManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.BaseColumns
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Note
import android.provider.ContactsContract.CommonDataKinds.Organization
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.Contacts
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.Directory
import android.provider.ContactsContract.PhoneLookup
import android.provider.ContactsContract.RawContacts
import android.util.Log

/**
 * Contacts directory provider (same mechanism the Exchange GAL used).
 * ContactsProvider forwards searches made in Contacts/Dialer to these URIs;
 * each one becomes a single LDAP query with a small size limit.
 */
class DirectoryProvider : ContentProvider() {

    companion object {
        private const val TAG = "LdapContacts"
        private const val AUTHORITY = "org.ldapcontacts.directory"
        private const val MIN_QUERY = 3
        private const val DEFAULT_LIMIT = 20
        private const val MAX_LIMIT = 25

        private const val DIRECTORIES = 1
        private const val CONTACTS_FILTER = 2
        private const val CONTACT_LOOKUP = 3
        private const val PHONE_FILTER = 4
        private const val EMAIL_FILTER = 5
        private const val PHONE_LOOKUP = 6

        private val matcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, "directories", DIRECTORIES)
            addURI(AUTHORITY, "contacts/filter/*", CONTACTS_FILTER)
            addURI(AUTHORITY, "contacts/lookup/*/entities", CONTACT_LOOKUP)
            addURI(AUTHORITY, "contacts/lookup/*/#/entities", CONTACT_LOOKUP)
            addURI(AUTHORITY, "data/phones/filter/*", PHONE_FILTER)
            addURI(AUTHORITY, "data/callables/filter/*", PHONE_FILTER)
            addURI(AUTHORITY, "data/emails/filter/*", EMAIL_FILTER)
            addURI(AUTHORITY, "phone_lookup/*", PHONE_LOOKUP)
        }
    }

    override fun onCreate() = true

    override fun query(
        uri: Uri, projection: Array<String>?, selection: String?,
        selectionArgs: Array<String>?, sortOrder: String?,
    ): Cursor? {
        val ctx = context ?: return null
        val limit = (uri.getQueryParameter(ContactsContract.LIMIT_PARAM_KEY)?.toIntOrNull()
            ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        Log.d(TAG, "query ${uri.path}")
        return try {
            when (matcher.match(uri)) {
                DIRECTORIES -> directories(ctx, projection)
                CONTACTS_FILTER -> contacts(projection, search(ctx, uri.lastPathSegment, limit))
                PHONE_FILTER -> phones(projection, search(ctx, uri.lastPathSegment, limit))
                EMAIL_FILTER -> emails(projection, search(ctx, uri.lastPathSegment, limit))
                CONTACT_LOOKUP -> entities(ctx, projection, Person.fromLookupKey(uri.pathSegments[2]))
                PHONE_LOOKUP -> phoneLookup(ctx, projection, uri.lastPathSegment)
                else -> null
            }
        } catch (e: Exception) {
            Log.w(TAG, "query failed: ${uri.path}", e)
            MatrixCursor(projection ?: arrayOf())
        }
    }

    private fun search(ctx: Context, q: String?, limit: Int): List<Person> {
        if (q == null || q.trim().length < MIN_QUERY) return emptyList()
        return try {
            LdapClient.search(ctx, q.trim(), limit)
        } catch (e: Credentials.Rejected) {
            listOf(Person.PASSWORD_REJECTED) // never fail silently
        } catch (e: Credentials.NotSet) {
            emptyList()
        }
    }

    /** Builds a cursor with exactly the columns the caller asked for; unknown ones are null. */
    private fun cursor(cols: Array<String>, rows: List<Map<String, Any?>>): Cursor {
        val c = MatrixCursor(cols, rows.size)
        rows.forEach { r -> c.addRow(cols.map { r[it] }) }
        return c
    }

    private fun contactCols(p: Person, id: Long): Map<String, Any?> = mapOf(
        BaseColumns._ID to id,
        Contacts.DISPLAY_NAME to p.name,
        Contacts.DISPLAY_NAME_ALTERNATIVE to p.alt,
        Contacts.SORT_KEY_PRIMARY to p.name,
        Contacts.SORT_KEY_ALTERNATIVE to p.alt,
        Contacts.DISPLAY_NAME_SOURCE to ContactsContract.DisplayNameSources.STRUCTURED_NAME,
        Contacts.LOOKUP_KEY to p.lookupKey(),
        Contacts.HAS_PHONE_NUMBER to if (p.numbers().isEmpty()) 0 else 1,
        Contacts.STARRED to 0,
        Contacts.IN_VISIBLE_GROUP to 1,
    )

    private fun directories(ctx: Context, projection: Array<String>?): Cursor {
        val cols = projection ?: arrayOf(
            Directory.ACCOUNT_NAME, Directory.ACCOUNT_TYPE, Directory.DISPLAY_NAME,
            Directory.TYPE_RESOURCE_ID, Directory.EXPORT_SUPPORT,
            Directory.SHORTCUT_SUPPORT, Directory.PHOTO_SUPPORT,
        )
        val accounts = AccountManager.get(ctx).getAccountsByType(Store.ACCOUNT_TYPE)
        Log.i(TAG, "directories queried by uid ${android.os.Binder.getCallingUid()}: ${accounts.size} account(s)")
        val rows = accounts.map { a ->
            mapOf(
                Directory.ACCOUNT_NAME to a.name,
                Directory.ACCOUNT_TYPE to a.type,
                // The server host: it is what tells the user which directory these
                // results come from, and it is the one thing they configured.
                Directory.DISPLAY_NAME to Config.server(ctx),
                Directory.TYPE_RESOURCE_ID to R.string.directory_label,
                Directory.EXPORT_SUPPORT to Directory.EXPORT_SUPPORT_ANY_ACCOUNT,
                Directory.SHORTCUT_SUPPORT to Directory.SHORTCUT_SUPPORT_DATA_ITEMS_ONLY,
                Directory.PHOTO_SUPPORT to Directory.PHOTO_SUPPORT_NONE,
            )
        }
        return cursor(cols, rows)
    }

    private fun contacts(projection: Array<String>?, people: List<Person>): Cursor {
        val cols = projection ?: arrayOf(
            Contacts._ID, Contacts.DISPLAY_NAME, Contacts.LOOKUP_KEY, Contacts.HAS_PHONE_NUMBER)
        return cursor(cols, people.mapIndexed { i, p -> contactCols(p, i + 1L) })
    }

    private fun phones(projection: Array<String>?, people: List<Person>): Cursor {
        val cols = projection ?: arrayOf(
            Phone._ID, Phone.CONTACT_ID, Phone.DISPLAY_NAME, Phone.NUMBER, Phone.TYPE, Phone.LOOKUP_KEY)
        val rows = mutableListOf<Map<String, Any?>>()
        var dataId = 1L
        people.forEachIndexed { i, p ->
            for ((num, type) in p.numbers()) {
                rows += contactCols(p, i + 1L) + mapOf(
                    Data._ID to dataId++,
                    Data.CONTACT_ID to i + 1L,
                    Data.RAW_CONTACT_ID to i + 1L,
                    Data.MIMETYPE to Phone.CONTENT_ITEM_TYPE,
                    Phone.NUMBER to num,
                    Phone.NORMALIZED_NUMBER to num,
                    Phone.TYPE to type,
                    Phone.LABEL to null,
                )
            }
        }
        return cursor(cols, rows)
    }

    private fun emails(projection: Array<String>?, people: List<Person>): Cursor {
        val cols = projection ?: arrayOf(
            Email._ID, Email.CONTACT_ID, Email.DISPLAY_NAME, Email.ADDRESS, Email.TYPE, Email.LOOKUP_KEY)
        val rows = mutableListOf<Map<String, Any?>>()
        var dataId = 1L
        people.forEachIndexed { i, p ->
            for (mail in p.mails) {
                rows += contactCols(p, i + 1L) + mapOf(
                    Data._ID to dataId++,
                    Data.CONTACT_ID to i + 1L,
                    Data.RAW_CONTACT_ID to i + 1L,
                    Data.MIMETYPE to Email.CONTENT_ITEM_TYPE,
                    Email.ADDRESS to mail,
                    Email.TYPE to Email.TYPE_WORK,
                )
            }
        }
        return cursor(cols, rows)
    }

    /** Contact detail view: one row per data item, rebuilt from the lookup key. */
    private fun entities(ctx: Context, projection: Array<String>?, p: Person?): Cursor {
        val cols = projection ?: arrayOf(
            Contacts.Entity.CONTACT_ID, Contacts.Entity.RAW_CONTACT_ID, Contacts.Entity.DATA_ID,
            Data.MIMETYPE, Data.DATA1, Data.DATA2, Data.DATA3, Contacts.DISPLAY_NAME,
        )
        if (p == null) return MatrixCursor(cols)
        val common = contactCols(p, 1L) + mapOf(
            Contacts.Entity.CONTACT_ID to 1L,
            Contacts.Entity.RAW_CONTACT_ID to 1L,
            RawContacts.ACCOUNT_NAME to Store.account(ctx)?.name,
            RawContacts.ACCOUNT_TYPE to Store.ACCOUNT_TYPE,
            "account_type_and_data_set" to Store.ACCOUNT_TYPE,
            RawContacts.DIRTY to 0,
            RawContacts.DELETED to 0,
            RawContacts.VERSION to 1,
            Data.IS_PRIMARY to 0,
            Data.IS_SUPER_PRIMARY to 0,
        )
        val items = mutableListOf<Map<String, Any?>>()
        items += mapOf(
            Data.MIMETYPE to StructuredName.CONTENT_ITEM_TYPE,
            StructuredName.DISPLAY_NAME to p.name,
            StructuredName.GIVEN_NAME to p.given,
            StructuredName.FAMILY_NAME to p.family,
        )
        for ((num, type) in p.numbers()) items += mapOf(
            Data.MIMETYPE to Phone.CONTENT_ITEM_TYPE,
            Phone.NUMBER to num,
            Phone.NORMALIZED_NUMBER to num,
            Phone.TYPE to type,
        )
        for (mail in p.mails) items += mapOf(
            Data.MIMETYPE to Email.CONTENT_ITEM_TYPE,
            Email.ADDRESS to mail,
            Email.TYPE to Email.TYPE_WORK,
        )
        // No COMPANY: the directory does not publish one, and the server host is a
        // poor stand-in. The section header in Contacts already names the server.
        if (p.unit.isNotEmpty() || p.office.isNotEmpty()) items += mapOf(
            Data.MIMETYPE to Organization.CONTENT_ITEM_TYPE,
            Organization.DEPARTMENT to p.unit,
            Organization.OFFICE_LOCATION to p.office,
            Organization.TYPE to Organization.TYPE_WORK,
        )
        val note = listOfNotNull(
            p.office.takeIf { it.isNotEmpty() }?.let { "Office $it" },
            p.uid.takeIf { it.isNotEmpty() }?.let { "Login $it" },
        ).joinToString("\n")
        if (note.isNotEmpty()) items += mapOf(Data.MIMETYPE to Note.CONTENT_ITEM_TYPE, Note.NOTE to note)

        return cursor(cols, items.mapIndexed { i, m -> common + mapOf(Contacts.Entity.DATA_ID to i + 1L) + m })
    }

    private fun phoneLookup(ctx: Context, projection: Array<String>?, raw: String?): Cursor {
        val cols = projection ?: arrayOf(
            PhoneLookup._ID, PhoneLookup.DISPLAY_NAME, PhoneLookup.LOOKUP_KEY,
            PhoneLookup.NUMBER, PhoneLookup.TYPE)
        val e164 = Numbers.toE164(ctx, raw) ?: return MatrixCursor(cols)
        val p = try {
            LdapClient.byNumber(ctx, e164)
        } catch (e: Credentials.Rejected) {
            null
        } catch (e: Credentials.NotSet) {
            null
        } ?: return MatrixCursor(cols)
        val type = if (e164 in p.mobiles) Phone.TYPE_WORK_MOBILE else Phone.TYPE_WORK
        return cursor(cols, listOf(contactCols(p, 1L) + mapOf(
            PhoneLookup.NUMBER to e164,
            PhoneLookup.NORMALIZED_NUMBER to e164,
            PhoneLookup.TYPE to type,
        )))
    }

    override fun getType(uri: Uri): String? = when (matcher.match(uri)) {
        CONTACT_LOOKUP -> Contacts.CONTENT_ITEM_TYPE
        CONTACTS_FILTER -> Contacts.CONTENT_TYPE
        else -> null
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, s: String?, a: Array<String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<String>?): Int =
        throw UnsupportedOperationException()
}
