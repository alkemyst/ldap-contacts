package org.ldapcontacts

import android.content.Context
import com.unboundid.ldap.sdk.Filter
import com.unboundid.ldap.sdk.LDAPConnection
import com.unboundid.ldap.sdk.LDAPConnectionOptions
import com.unboundid.ldap.sdk.LDAPException
import com.unboundid.ldap.sdk.LDAPSearchException
import com.unboundid.ldap.sdk.ResultCode
import com.unboundid.ldap.sdk.SearchRequest
import com.unboundid.ldap.sdk.SearchScope
import com.unboundid.util.ssl.HostNameSSLSocketVerifier
import javax.net.ssl.SSLContext

/**
 * LDAPS client for an address book, bound as the user with their own password, the
 * way a mail client is configured against a corporate directory. Server, base DN and
 * bind DN pattern come from [Config]; the attribute names here do not.
 *
 * The certificate is validated against the system trust store and the host name is
 * checked, so the server needs a publicly trusted certificate; no CA is shipped with
 * the app.
 *
 * One connection is kept and reused across queries. A refused password throws
 * [Credentials.Rejected] and stops every further attempt until the user types it
 * again; see the note on account lockout there.
 */
object LdapClient {
    private const val MAX_RESULTS = 25
    private const val MEMO_MS = 15_000L

    private val ATTRS = arrayOf(
        "cn", "displayName", "givenName", "sn", "uid", "ou", "mail",
        "telephoneNumber", "mobile", "physicalDeliveryOfficeName",
    )

    private var conn: LDAPConnection? = null
    private var connHost: String? = null
    private var connDn: String? = null

    /**
     * Checks a whole configuration with one bind; used by the setup screen before it
     * stores anything.
     */
    fun testBind(host: String, bindDn: String, password: String) =
        open(host, bindDn, password).close()

    @Synchronized
    fun reset() {
        conn?.close()
        conn = null
        connHost = null
        connDn = null
    }

    fun clearMemo() = synchronized(memo) { memo.clear() }

    // Contacts/Dialer send the same query up to three times (contacts, phones, emails):
    // answer the repeats from memory for a few seconds. Never written to disk.
    private val memo = object : LinkedHashMap<String, Pair<Long, List<Person>>>() {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Pair<Long, List<Person>>>) = size > 8
    }

    /** Name tokens (all must match) or a partial number; empty if the query is too vague. */
    @Synchronized
    fun search(ctx: Context, q: String, limit: Int): List<Person> {
        val now = System.currentTimeMillis()
        synchronized(memo) {
            memo[q]?.takeIf { now - it.first < MEMO_MS }?.let { return it.second.take(limit) }
        }
        val filter = searchFilter(q) ?: return emptyList()
        val people = query(ctx, filter, MAX_RESULTS).sortedBy { it.alt.lowercase() }
        synchronized(memo) { memo[q] = now to people }
        return people.take(limit)
    }

    /** Exact reverse lookup of an E.164 number. */
    fun byNumber(ctx: Context, e164: String): Person? =
        query(
            ctx,
            Filter.createORFilter(
                Filter.createEqualityFilter("telephoneNumber", e164),
                Filter.createEqualityFilter("mobile", e164),
            ),
            1,
        ).firstOrNull()

    private fun open(host: String, bindDn: String, password: String): LDAPConnection {
        val opts = LDAPConnectionOptions()
        opts.setConnectTimeoutMillis(5_000)
        opts.setResponseTimeoutMillis(8_000L)
        opts.setSSLSocketVerifier(HostNameSSLSocketVerifier(true))
        val c = LDAPConnection(SSLContext.getDefault().socketFactory, opts, host, Config.PORT)
        try {
            c.bind(bindDn, password)
        } catch (e: LDAPException) {
            c.close()
            if (e.resultCode == ResultCode.INVALID_CREDENTIALS) throw Credentials.Rejected()
            throw e
        }
        return c
    }

    @Synchronized
    private fun query(ctx: Context, filter: Filter, limit: Int): List<Person> {
        val cred = Credentials.require(ctx)
        val host = Config.server(ctx)
        val bindDn = Config.bindDn(Config.bindDnPattern(ctx), cred.login)
        for (attempt in 0..1) {
            try {
                var c = conn
                // Also reconnects when the configuration changed under us.
                if (c == null || !c.isConnected || connHost != host || connDn != bindDn) {
                    reset()
                    c = open(host, bindDn, cred.password)
                    conn = c
                    connHost = host
                    connDn = bindDn
                }
                val req = SearchRequest(Config.baseDn(ctx), SearchScope.SUB, filter, *ATTRS)
                req.setSizeLimit(limit)
                req.setTimeLimitSeconds(5)
                val res = try {
                    c.search(req)
                } catch (e: LDAPSearchException) {
                    if (e.resultCode == ResultCode.SIZE_LIMIT_EXCEEDED) e.searchResult else throw e
                }
                return res.searchEntries.map(Person::fromEntry)
            } catch (e: Credentials.Rejected) {
                Store.setPasswordRejected(ctx, true)
                Store.notifyPasswordRejected(ctx)
                reset()
                throw e
            } catch (e: LDAPException) {
                reset()
                // A usable result code means a real LDAP error, not a stale connection:
                // only the latter is worth one retry.
                if (attempt == 1 || ResultCode.isConnectionUsable(e.resultCode)) throw e
            }
        }
        return emptyList()
    }

    /**
     * Builds the search filter. Values go through the UnboundID filter API, which
     * escapes them, so a `*` typed by the user stays a literal asterisk instead of
     * turning into a wildcard that would scrape the whole directory.
     */
    fun searchFilter(q: String): Filter? {
        val compact = q.replace(Regex("[\\s()./-]"), "")
        if (compact.isNotEmpty() && compact.all { it.isDigit() || it == '+' }) {
            var digits = compact.filter(Char::isDigit)
            if (!compact.startsWith("+")) {
                digits = when {
                    digits.startsWith("00") -> digits.drop(2)
                    digits.startsWith("0") -> "41" + digits.drop(1)
                    else -> digits
                }
            }
            if (digits.length < 4) return null
            return Filter.createORFilter(
                Filter.createSubstringFilter("telephoneNumber", null, arrayOf(digits), null),
                Filter.createSubstringFilter("mobile", null, arrayOf(digits), null),
            )
        }
        val tokens = q.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        return Filter.createANDFilter(tokens.map { t ->
            Filter.createORFilter(
                listOf("sn", "givenName", "uid", "cn").map {
                    Filter.createSubstringFilter(it, t, null, null)
                },
            )
        })
    }
}
