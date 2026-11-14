package dev.moa.trip

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.moa.trip.core.DestinationPrompt
import kotlinx.serialization.decodeFromString

/** Explicit immutable PendingIntents only; no activity launch or background permission bypass. */
class DestinationActionReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context,intent: Intent) {
        if(intent.action!=RESPOND || !intent.hasExtra("approve")) return
        val app=context.applicationContext as TripApplication
        val prompt=runCatching {
            app.json.decodeFromString<DestinationPrompt>(intent.getStringExtra("prompt") ?: return)
        }.getOrNull() ?: return
        if(app.trip.state.value.prompt!=prompt) return
        // The user-started trip service owns the process lifetime. The controller serializes
        // actions and revalidates room/epoch/revision/native route/UX before any SDK write.
        app.trip.respondDestination(prompt,intent.getBooleanExtra("approve",false))
    }
    companion object { const val RESPOND="dev.moa.trip.RESPOND_DESTINATION" }
}
