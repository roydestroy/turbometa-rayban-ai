package com.smartview.glassai.services

/** Tracks a wake phrase followed by a question, including both in the same utterance. */
class WakeQuestion {
    var listening = false
        private set
    private var started = 0L
    private var partial = ""
    private val prefix = Regex("^hey vision\\b[ ,.!?]*", RegexOption.IGNORE_CASE)

    fun accept(text: String, final: Boolean, now: Long): String? {
        val clean = text.trim()
        if (!listening) {
            if (!prefix.containsMatchIn(clean)) return null
            listening = true
            started = now
        }
        partial = clean.replace(prefix, "").trim().removePrefix("please ")
        if (partial == "please") partial = ""
        return if (final && partial.isNotBlank()) partial else null
    }

    fun expired(now: Long) = listening && now - started >= 20000
    // Do not execute a potentially incomplete destination at the time limit.
}
