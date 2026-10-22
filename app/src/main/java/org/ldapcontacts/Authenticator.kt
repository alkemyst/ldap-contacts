package org.ldapcontacts

import android.accounts.AbstractAccountAuthenticator
import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.accounts.AccountManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder

class AuthenticatorService : Service() {
    private lateinit var auth: Authenticator
    override fun onCreate() { auth = Authenticator(this) }
    override fun onBind(intent: Intent?): IBinder = auth.iBinder
}

/** Minimal authenticator: "Add account" in system settings opens SetupActivity. */
class Authenticator(private val ctx: Context) : AbstractAccountAuthenticator(ctx) {

    private fun setupIntent(r: AccountAuthenticatorResponse?) = Bundle().apply {
        putParcelable(
            AccountManager.KEY_INTENT,
            Intent(ctx, SetupActivity::class.java)
                .putExtra(AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE, r),
        )
    }

    override fun addAccount(
        r: AccountAuthenticatorResponse?, accountType: String?, authTokenType: String?,
        requiredFeatures: Array<out String>?, options: Bundle?,
    ): Bundle = setupIntent(r)

    override fun updateCredentials(
        r: AccountAuthenticatorResponse?, account: Account?, authTokenType: String?, options: Bundle?,
    ): Bundle = setupIntent(r)

    override fun editProperties(r: AccountAuthenticatorResponse?, accountType: String?): Bundle? = null
    override fun confirmCredentials(r: AccountAuthenticatorResponse?, account: Account?, options: Bundle?): Bundle? = null
    override fun getAuthToken(
        r: AccountAuthenticatorResponse?, account: Account?, authTokenType: String?, options: Bundle?,
    ): Bundle? = null
    override fun getAuthTokenLabel(authTokenType: String?): String? = null
    /** Removing the account in system settings also forgets the stored password. */
    override fun getAccountRemovalAllowed(r: AccountAuthenticatorResponse?, account: Account?): Bundle {
        Credentials.signOut(ctx, removeAccount = false)
        return Bundle().apply { putBoolean(AccountManager.KEY_BOOLEAN_RESULT, true) }
    }
    override fun hasFeatures(
        r: AccountAuthenticatorResponse?, account: Account?, features: Array<out String>?,
    ): Bundle = Bundle().apply { putBoolean(AccountManager.KEY_BOOLEAN_RESULT, false) }
}
