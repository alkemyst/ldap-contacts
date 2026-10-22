package org.ldapcontacts

import android.accounts.Account
import android.accounts.AccountManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * Account (AccountManager, no secret in it: the password lives in [Credentials]),
 * state flags and notifications. The directory settings are in [Config].
 */
object Store {
    const val ACCOUNT_TYPE = "org.ldapcontacts.account"
    private const val PREFS = "state"
    private const val KEY_PASSWORD_REJECTED = "password_rejected"
    private const val CH_AUTH = "auth"
    private const val CH_CALLER = "caller"
    private const val ID_PASSWORD_REJECTED = 1

    fun account(ctx: Context): Account? =
        AccountManager.get(ctx).getAccountsByType(ACCOUNT_TYPE).firstOrNull()

    fun setAccount(ctx: Context, login: String) {
        val am = AccountManager.get(ctx)
        am.getAccountsByType(ACCOUNT_TYPE).filter { it.name != login }
            .forEach { am.removeAccountExplicitly(it) }
        am.addAccountExplicitly(Account(login, ACCOUNT_TYPE), null, null)
    }

    fun removeAccount(ctx: Context) {
        val am = AccountManager.get(ctx)
        am.getAccountsByType(ACCOUNT_TYPE).forEach { am.removeAccountExplicitly(it) }
    }

    fun passwordRejected(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_PASSWORD_REJECTED, false)

    fun setPasswordRejected(ctx: Context, v: Boolean) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_PASSWORD_REJECTED, v).apply()

    private fun nm(ctx: Context): NotificationManager {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_AUTH, "Password", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(
            NotificationChannel(CH_CALLER, "Calls from the directory", NotificationManager.IMPORTANCE_HIGH))
        return nm
    }

    /** Stays until the user enters the password again: searches must never stop silently. */
    fun notifyPasswordRejected(ctx: Context) {
        val pi = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, SetupActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(ctx, CH_AUTH)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("LDAP Contacts: password refused")
            .setContentText("Searches are paused. Tap to enter your password again.")
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        nm(ctx).notify(ID_PASSWORD_REJECTED, n)
    }

    fun cancelPasswordRejected(ctx: Context) = nm(ctx).cancel(ID_PASSWORD_REJECTED)

    fun notifyCaller(ctx: Context, p: Person, number: String) {
        val n = Notification.Builder(ctx, CH_CALLER)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle(p.name)
            .setContentText(listOf(p.unit, p.office).filter { it.isNotEmpty() }.joinToString(" · "))
            .setSubText(number)
            .setCategory(Notification.CATEGORY_CALL)
            .setTimeoutAfter(120_000)
            .setAutoCancel(true)
            .build()
        nm(ctx).notify(number.hashCode(), n)
    }
}
