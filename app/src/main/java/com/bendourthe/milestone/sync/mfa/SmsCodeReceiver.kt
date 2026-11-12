package com.bendourthe.milestone.sync.mfa

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.bendourthe.milestone.MileStoneApplication

/** Passes incoming texts to the in-memory code inbox; texts are never stored, logged, or sent anywhere. */
class SmsCodeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val body = Telephony.Sms.Intents.getMessagesFromIntent(intent)?.joinToString("") { it.messageBody.orEmpty() } ?: return
        (context.applicationContext as MileStoneApplication).container.codeInbox.offer(body)
    }
}
