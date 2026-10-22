package org.ldapcontacts

import android.content.Context
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import android.util.Base64
import com.unboundid.ldap.sdk.SearchResultEntry

data class Person(
    val name: String,
    val given: String,
    val family: String,
    val unit: String,
    val office: String,
    val mails: List<String>,
    val phones: List<String>,
    val mobiles: List<String>,
    /** The login name (LDAP uid), shown in the contact's note. */
    val uid: String = "",
) {
    val alt: String
        get() = if (family.isNotEmpty() && given.isNotEmpty()) "$family, $given" else name

    /** (number, Phone.TYPE) pairs. */
    fun numbers(): List<Pair<String, Int>> =
        phones.map { it to Phone.TYPE_WORK } + mobiles.map { it to Phone.TYPE_WORK_MOBILE }

    /**
     * The whole record is encoded in the lookup key, so opening a result
     * needs no second query and nothing is cached on the phone.
     */
    fun lookupKey(): String {
        val raw = listOf(
            name, given, family, unit, office,
            mails.joinToString(RS), phones.joinToString(RS), mobiles.joinToString(RS), uid,
        ).joinToString(US)
        return Base64.encodeToString(raw.toByteArray(), B64)
    }

    companion object {
        private const val US = "\u001f"
        private const val RS = "\u001e"
        private const val B64 = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING

        /** Shown as the only search result when the stored password was refused. */
        val PASSWORD_REJECTED = Person(
            name = "Password refused: open LDAP Contacts to enter it again",
            given = "", family = "", unit = "", office = "",
            mails = emptyList(), phones = emptyList(), mobiles = emptyList(),
        )

        fun fromLookupKey(key: String?): Person? = key?.let {
            runCatching {
                val f = String(Base64.decode(it, B64)).split(US)
                fun l(s: String) = if (s.isEmpty()) emptyList() else s.split(RS)
                // Keys made before the uid was added have 8 fields.
                Person(f[0], f[1], f[2], f[3], f[4], l(f[5]), l(f[6]), l(f[7]), f.getOrElse(8) { "" })
            }.getOrNull()
        }

        /** One LDAP entry; the attribute names are the ones the directory publishes. */
        fun fromEntry(e: SearchResultEntry): Person {
            fun a(n: String) = e.getAttributeValue(n)?.trim() ?: ""
            fun m(n: String) =
                e.getAttributeValues(n)?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
            val given = a("givenName")
            val family = a("sn")
            return Person(
                name = a("displayName").ifEmpty { a("cn") }.ifEmpty { "$given $family".trim() },
                given = given,
                family = family,
                unit = a("ou"),
                office = a("physicalDeliveryOfficeName"),
                mails = m("mail"),
                phones = m("telephoneNumber"),
                mobiles = m("mobile"),
                uid = a("uid"),
            )
        }
    }
}

/**
 * Phone number handling. Every incoming number is looked up: the app knows no
 * directory's number ranges, so it cannot tell in advance which ones are worth a query.
 */
object Numbers {
    /**
     * Normalises to the compact E.164 form the directory is expected to store
     * (country code and number, no spaces). A national number gets the country code of the mobile
     * network, or of the SIM when there is no network.
     */
    fun toE164(ctx: Context, raw: String?): String? {
        val s = raw?.filter { it.isDigit() || it == '+' }?.takeIf { it.isNotEmpty() } ?: return null
        if (s.startsWith("+")) return s.takeIf { it.length >= 9 }
        if (s.startsWith("00")) return ("+" + s.drop(2)).takeIf { it.length >= 9 }
        val tm = ctx.getSystemService(TelephonyManager::class.java)
        val country = tm?.networkCountryIso?.takeIf { it.isNotEmpty() }
            ?: tm?.simCountryIso?.takeIf { it.isNotEmpty() }
            ?: return null
        return PhoneNumberUtils.formatNumberToE164(s, country.uppercase())
    }
}
