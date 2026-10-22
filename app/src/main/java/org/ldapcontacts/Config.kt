package org.ldapcontacts

import android.content.Context

/**
 * The directory settings: server, base DN and bind DN pattern. They are asked for on
 * first run ([ConfigActivity]), by hand or from a QR code, and stored in plain
 * preferences; none of them is a secret (the password lives in [Credentials]).
 *
 * There are no working defaults: the app ships knowing no directory, and the values
 * below are only the examples shown as hints. What is still fixed in code, and would
 * be the next thing to open up: the port, the attribute names ([LdapClient] and
 * [Person.fromEntry]), and the phone number conventions in [Numbers].
 */
object Config {
    const val EXAMPLE_SERVER = "ldap.example.org"
    const val EXAMPLE_BASE_DN = "dc=example,dc=org"
    const val EXAMPLE_BIND_DN = "uid=%s,ou=people,dc=example,dc=org"

    /** LDAPS. Plain LDAP and StartTLS are deliberately not offered. */
    const val PORT = 636

    /** The placeholder the bind DN pattern uses for the login. */
    const val LOGIN_PLACEHOLDER = "%s"

    private const val PREFS = "config"
    private const val KEY_SERVER = "server"
    private const val KEY_BASE_DN = "base_dn"
    private const val KEY_BIND_DN = "bind_dn"

    /** False until the settings have been entered once; nothing can be searched before. */
    fun isConfigured(ctx: Context) = prefs(ctx).contains(KEY_SERVER)

    fun server(ctx: Context) = get(ctx, KEY_SERVER, EXAMPLE_SERVER)

    /** Search base, e.g. `dc=example,dc=org`. */
    fun baseDn(ctx: Context) = get(ctx, KEY_BASE_DN, EXAMPLE_BASE_DN)

    /** Bind DN pattern; [LOGIN_PLACEHOLDER] stands for the login. */
    fun bindDnPattern(ctx: Context) = get(ctx, KEY_BIND_DN, EXAMPLE_BIND_DN)

    /**
     * The bind DN for a login. A plain replacement, not String.format, so that a `%`
     * elsewhere in the DN cannot throw or be read as a format specifier.
     */
    fun bindDn(pattern: String, login: String) = pattern.replace(LOGIN_PLACEHOLDER, login)

    /** True when the pattern can actually produce a DN for a login. */
    fun isValidBindDnPattern(pattern: String) =
        pattern.contains(LOGIN_PLACEHOLDER) && pattern.length > LOGIN_PLACEHOLDER.length

    fun save(ctx: Context, server: String, baseDn: String, bindDnPattern: String) =
        prefs(ctx).edit()
            .putString(KEY_SERVER, server.trim())
            .putString(KEY_BASE_DN, baseDn.trim())
            .putString(KEY_BIND_DN, bindDnPattern.trim())
            .apply()

    private fun get(ctx: Context, key: String, fallback: String) =
        prefs(ctx).getString(key, null)?.takeIf { it.isNotBlank() } ?: fallback

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Settings read from a QR code. A field is null when the code did not carry it. */
    class Scanned(val server: String?, val baseDn: String?, val bindDnPattern: String?) {
        val isEmpty get() = server == null && baseDn == null && bindDnPattern == null
    }

    /**
     * Reads `key = value` lines, which is what the configuration QR codes carry:
     *
     *     server="ldap.example.org"
     *     base_dn="dc=example,dc=org"
     *     bind_dn="uid=%s,ou=people,dc=example,dc=org"
     *
     * Deliberately lenient, because a QR code cannot be corrected once printed:
     * `=` or `:` separates, quotes are optional, spaces and blank lines are ignored,
     * `#` starts a comment, key spelling ignores case and punctuation, and only the
     * first separator counts so a DN keeps its own `=` signs. Keys the app does not
     * know are skipped rather than failing the whole code.
     *
     * A QR code never carries the login or the password: it is configuration meant to
     * be shared or printed, not a credential.
     */
    fun parse(text: String): Scanned {
        val values = mutableMapOf<String, String>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val cut = line.indexOfFirst { it == '=' || it == ':' }
            if (cut <= 0) continue
            val key = line.substring(0, cut).filter { it.isLetterOrDigit() }.lowercase()
            val value = unquote(line.substring(cut + 1).trim())
            if (key.isNotEmpty() && value.isNotEmpty()) values[key] = value
        }
        return Scanned(
            server = values["server"]?.let(::cleanServer),
            baseDn = values["basedn"] ?: values["base"],
            bindDnPattern = values["binddn"] ?: values["binddnpattern"] ?: values["bind"],
        )
    }

    private fun unquote(s: String): String {
        if (s.length < 2) return s
        val q = s.first()
        return if ((q == '"' || q == '\'') && s.last() == q) s.substring(1, s.length - 1) else s
    }

    /** Accepts `ldaps://host/` as well as a bare host name. */
    private fun cleanServer(s: String) =
        s.removePrefix("ldaps://").removePrefix("ldap://").trimEnd('/').trim()
}
