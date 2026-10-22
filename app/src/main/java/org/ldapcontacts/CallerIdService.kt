package org.ldapcontacts

import android.telecom.Call
import android.telecom.CallScreeningService

/**
 * Caller ID for numbers that are not in local contacts.
 * Never blocks or delays the call: it allows it immediately, then does one
 * reverse lookup and, when the directory knows the number, posts a notification
 * with the name.
 */
class CallerIdService : CallScreeningService() {
    override fun onScreenCall(details: Call.Details) {
        respondToCall(details, CallResponse.Builder().build())
        if (details.callDirection != Call.Details.DIRECTION_INCOMING) return
        val app = applicationContext
        val e164 = Numbers.toE164(app, details.handle?.schemeSpecificPart) ?: return
        Thread {
            val p = runCatching { LdapClient.byNumber(app, e164) }.getOrNull() ?: return@Thread
            Store.notifyCaller(app, p, e164)
        }.start()
    }
}
