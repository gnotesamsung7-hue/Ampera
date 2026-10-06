package com.example.eyebot

import android.content.Context

/** Stores Ampera's story progress and running gags in SharedPreferences. */
class PrefsChatMemory(context: Context) : ChatMemory {
    private val prefs = context.getSharedPreferences("voltnutt_chat", Context.MODE_PRIVATE)
    override fun getInt(key: String, default: Int) = prefs.getInt(key, default)
    override fun putInt(key: String, value: Int) { prefs.edit().putInt(key, value).apply() }
}
