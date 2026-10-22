package org.ldapcontacts

import android.Manifest
import android.accounts.AccountAuthenticatorResponse
import android.accounts.AccountManager
import android.app.Activity
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.text.InputType
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors

class SetupActivity : Activity() {
    companion object {
        private const val RC_PERMISSIONS = 1
        private const val RC_SCREENING = 2
        private const val RC_CONFIG = 3
    }

    private val exec = Executors.newSingleThreadExecutor()
    private lateinit var directory: TextView
    private lateinit var login: EditText
    private lateinit var password: EditText
    private lateinit var signIn: Button
    private lateinit var changeConfig: Button
    private lateinit var status: TextView
    private lateinit var query: EditText
    private lateinit var results: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun label(s: String) = TextView(this).apply { text = s; setPadding(0, pad, 0, pad / 2) }

        directory = TextView(this)
        login = EditText(this).apply {
            hint = "Login"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }
        password = EditText(this).apply {
            hint = "Password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        signIn = Button(this).apply { setOnClickListener { onSignInClicked() } }
        changeConfig = Button(this).apply {
            text = "Change directory"
            setOnClickListener { openConfig() }
        }
        status = TextView(this)
        query = EditText(this).apply { hint = "Try a search (name or number)" }
        results = TextView(this).apply { setTextIsSelectable(true) }

        root.addView(label("Account"))
        root.addView(directory)
        root.addView(login)
        root.addView(password)
        root.addView(signIn)
        root.addView(status)
        root.addView(changeConfig)
        root.addView(label("Test"))
        root.addView(query)
        root.addView(Button(this).apply { text = "Search"; setOnClickListener { test() } })
        root.addView(results)
        root.addView(label("Name on incoming calls (optional)"))
        root.addView(Button(this).apply { text = "Enable caller ID"; setOnClickListener { requestScreening() } })

        setContentView(ScrollView(this).apply { fitsSystemWindows = true; addView(root) })
        refreshStatus()

        // Nothing here works without a directory: ask for one before anything else.
        if (Config.isConfigured(this)) requestNeededPermissions() else openConfig()
    }

    private fun requestNeededPermissions() {
        val wanted = buildList {
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            add(Manifest.permission.READ_CONTACTS)
            add(Manifest.permission.WRITE_CONTACTS)
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) requestPermissions(wanted.toTypedArray(), RC_PERMISSIONS)
        else if (Store.account(this) != null) nudgeDirectoryRescan()
    }

    private fun openConfig() {
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(this, ConfigActivity::class.java), RC_CONFIG)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != RC_CONFIG) return
        refreshStatus()
        // The permission prompts were skipped while no directory was set.
        if (Config.isConfigured(this)) requestNeededPermissions()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (Store.account(this) != null) nudgeDirectoryRescan()
    }

    private fun directoryRegistered(): Boolean? {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        return runCatching {
            contentResolver.query(
                ContactsContract.Directory.CONTENT_URI, arrayOf(ContactsContract.Directory._ID),
                "${ContactsContract.Directory.PACKAGE_NAME}=?", arrayOf(packageName), null,
            )?.use { it.count > 0 }
        }.getOrNull()
    }

    /**
     * The state follows the wallet, not the account: an account left behind by another
     * build would otherwise look like a working sign-in while no password is stored.
     */
    private fun refreshStatus() {
        val configured = Config.isConfigured(this)
        val stored = Credentials.load(this)
        val rejected = Store.passwordRejected(this)
        val signedIn = stored != null && !rejected

        directory.text = if (configured) "Directory: ${Config.server(this)}" else "No directory set."
        signIn.text = if (signedIn) "Sign out" else "Sign in"
        signIn.isEnabled = configured
        login.isEnabled = configured && !signedIn
        password.isEnabled = configured && !signedIn
        // Changing the directory while signed in would leave a password bound to the old one.
        changeConfig.isEnabled = !signedIn
        if (signedIn) password.text = null
        else (stored?.login ?: Store.account(this)?.name)?.let { login.setText(it) }

        val dir = when (directoryRegistered()) {
            true -> "\nDirectory registered in the system contacts."
            false -> "\nDirectory NOT registered in the system contacts."
            null -> ""
        }
        status.text = when {
            !configured -> "Set the directory first."
            stored == null -> "Not signed in."
            rejected -> "Password refused: searches are paused until you enter it again."
            else -> "Signed in as ${stored.login}."
        } + dir
    }

    /**
     * Login and password are checked with one bind before they are stored, so a typo is
     * caught here instead of by the directory provider, where repeated attempts would
     * walk the account towards a lockout.
     */
    private fun onSignInClicked() {
        if (!Config.isConfigured(this)) {
            openConfig()
            return
        }
        if (Credentials.load(this) != null && !Store.passwordRejected(this)) {
            status.text = "Signing out…"
            exec.execute {
                Credentials.signOut(this)
                runOnUiThread { refreshStatus() }
            }
            return
        }
        val u = login.text.toString().trim()
        val p = password.text.toString()
        if (u.isEmpty() || p.isEmpty()) {
            status.text = "Enter your login and password."
            return
        }
        val host = Config.server(this)
        val bindDn = Config.bindDn(Config.bindDnPattern(this), u)
        status.text = "Checking…"
        exec.execute {
            val err = try {
                LdapClient.testBind(host, bindDn, p)
                Credentials.save(this, u, p)
                null
            } catch (e: Credentials.Rejected) {
                "Bind refused: check the login, the password and the bind DN pattern."
            } catch (e: Exception) {
                "Cannot reach $host: ${e.message}"
            }
            runOnUiThread {
                if (err != null) {
                    status.text = err
                    return@runOnUiThread
                }
                password.text = null
                nudgeDirectoryRescan()
                refreshStatus()
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<AccountAuthenticatorResponse>(
                    AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE
                )?.onResult(Bundle().apply {
                    putString(AccountManager.KEY_ACCOUNT_NAME, u)
                    putString(AccountManager.KEY_ACCOUNT_TYPE, Store.ACCOUNT_TYPE)
                })
            }
        }
    }

    /**
     * ContactsProvider skips directory providers of apps in "stopped" state, which is
     * the case right after install, and it does not rescan by itself afterwards.
     * notifyDirectoryChange() needs WRITE_CONTACTS; toggling one of our own components
     * sends PACKAGE_CHANGED instead, which triggers a rescan of this package.
     */
    private fun nudgeDirectoryRescan() {
        runCatching { ContactsContract.Directory.notifyDirectoryChange(contentResolver) }
            .onSuccess { Log.i("LdapContacts", "notifyDirectoryChange OK") }
            .onFailure { Log.w("LdapContacts", "notifyDirectoryChange failed", it) }
        // Scanning is asynchronous: check the result shortly after.
        status.postDelayed({ refreshStatus() }, 3_000)
        val cn = android.content.ComponentName(this, "$packageName.RescanToggle")
        val pm = packageManager
        pm.setComponentEnabledSetting(
            cn, android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            android.content.pm.PackageManager.DONT_KILL_APP)
        pm.setComponentEnabledSetting(
            cn, android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
            android.content.pm.PackageManager.DONT_KILL_APP)
    }

    private fun test() {
        val q = query.text.toString().trim()
        if (q.length < 3) {
            results.text = "At least 3 characters."
            return
        }
        results.text = "…"
        exec.execute {
            val out = try {
                LdapClient.search(this, q, 25).joinToString("\n\n") { p ->
                    (listOf("${p.name}  (${p.unit})") + p.numbers().map { it.first }).joinToString("\n")
                }.ifEmpty { "No results." }
            } catch (e: Credentials.Rejected) {
                "Password refused: enter it again."
            } catch (e: Credentials.NotSet) {
                "Sign in first."
            } catch (e: Exception) {
                "Error: ${e.message}"
            }
            runOnUiThread { results.text = out; refreshStatus() }
        }
    }

    private fun requestScreening() {
        val rm = getSystemService(RoleManager::class.java)
        when {
            !rm.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) ->
                status.text = "Call screening role not available on this phone."
            rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING) ->
                status.text = "Caller ID already enabled."
            else -> @Suppress("DEPRECATION")
                startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING), RC_SCREENING)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onDestroy() {
        exec.shutdown()
        super.onDestroy()
    }
}
