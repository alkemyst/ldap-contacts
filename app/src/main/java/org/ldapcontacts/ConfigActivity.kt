package org.ldapcontacts

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * First run: which directory to talk to. Shown until the three settings have been
 * entered once, by hand or from a QR code; after that [SetupActivity] takes over and
 * only asks for login and password.
 *
 * To start over, clear the app's data: there is no reset here, so a working setup
 * cannot be lost by a stray tap.
 */
class ConfigActivity : Activity() {
    companion object {
        private const val RC_SCAN = 1
    }

    private lateinit var server: EditText
    private lateinit var baseDn: EditText
    private lateinit var bindDn: EditText
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun note(s: String) = TextView(this).apply {
            text = s
            setPadding(0, 0, 0, pad)
        }
        fun field(hintText: String, value: String) = EditText(this).apply {
            hint = hintText
            // No autocorrect and no capitalisation: these are host names and DNs.
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setText(value)
        }

        val configured = Config.isConfigured(this)
        server = field(Config.EXAMPLE_SERVER, if (configured) Config.server(this) else "")
        baseDn = field(Config.EXAMPLE_BASE_DN, if (configured) Config.baseDn(this) else "")
        bindDn = field(Config.EXAMPLE_BIND_DN, if (configured) Config.bindDnPattern(this) else "")
        status = TextView(this)

        root.addView(note(
            "Which LDAP directory should Contacts search? Ask whoever runs it, or scan " +
            "the QR code they published. In the bind DN, ${Config.LOGIN_PLACEHOLDER} " +
            "stands for your login."
        ))
        root.addView(TextView(this).apply { text = "Server (LDAPS, port ${Config.PORT})" })
        root.addView(server)
        root.addView(TextView(this).apply { text = "Search base DN" })
        root.addView(baseDn)
        root.addView(TextView(this).apply { text = "Bind DN pattern" })
        root.addView(bindDn)
        root.addView(Button(this).apply {
            text = "Scan QR code"
            setOnClickListener { scan() }
        })
        root.addView(Button(this).apply {
            text = "Continue"
            setOnClickListener { save() }
        })
        root.addView(status)

        setContentView(ScrollView(this).apply { fitsSystemWindows = true; addView(root) })
    }

    /**
     * [ScanActivity] asks for the camera permission when it opens, so the app needs no
     * camera access until this button is used and the permission can be granted just
     * for that once.
     */
    private fun scan() {
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(this, ScanActivity::class.java), RC_SCAN)
        } catch (e: ActivityNotFoundException) {
            status.text = "No scanner on this phone: type the settings instead."
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != RC_SCAN) return
        val text = if (resultCode == RESULT_OK) data?.getStringExtra(ScanActivity.EXTRA_TEXT) else null
        if (text == null) {
            // Says why, when the scanner knew: no camera, permission refused, or a
            // failure worth reporting rather than hiding.
            status.text = data?.getStringExtra(ScanActivity.EXTRA_ERROR) ?: "No code scanned."
            return
        }
        val scanned = Config.parse(text)
        if (scanned.isEmpty) {
            status.text = "That code carries no directory settings."
            return
        }
        val filled = mutableListOf<String>()
        scanned.server?.let { server.setText(it); filled += "server" }
        scanned.baseDn?.let { baseDn.setText(it); filled += "base DN" }
        scanned.bindDnPattern?.let { bindDn.setText(it); filled += "bind DN" }
        // Shown rather than saved straight away: a QR code is worth a look before trusting it.
        status.text = "Scanned ${filled.joinToString(", ")}. Check the fields, then Continue."
    }

    private fun save() {
        val h = server.text.toString().trim()
        val base = baseDn.text.toString().trim()
        val pattern = bindDn.text.toString().trim()
        if (h.isEmpty() || base.isEmpty() || pattern.isEmpty()) {
            status.text = "Fill in all three, or scan a QR code that carries them."
            return
        }
        if (!Config.isValidBindDnPattern(pattern)) {
            status.text = "The bind DN pattern must contain ${Config.LOGIN_PLACEHOLDER}, " +
                "which stands for the login."
            return
        }
        Config.save(this, h, base, pattern)
        setResult(RESULT_OK)
        finish()
    }
}
