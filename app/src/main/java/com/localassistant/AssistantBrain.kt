package com.localassistant

import android.content.Context
import java.text.Normalizer
import kotlin.math.max

class AssistantBrain(context: Context) {
    private val preferences = context.getSharedPreferences("local_brain", Context.MODE_PRIVATE)

    fun learn(original: String, corrected: String) {
        val source = normalize(original)
        val target = corrected.trim()
        if (source.isNotBlank() && target.isNotBlank()) {
            preferences.edit().putString("learned:$source", target).apply()
        }
    }

    fun correctionFor(text: String): String? {
        val normalized = normalize(text)
        preferences.all.forEach { (key, value) ->
            if (key.startsWith("learned:") && value is String) {
                val sample = key.removePrefix("learned:")
                if (normalized == sample || similarity(normalized, sample) >= 0.86) return value
            }
        }
        return null
    }

    fun translateToArabic(text: String): String {
        val normalized = normalize(text)
        val phrases = mapOf(
            "hello" to "مرحبا",
            "good morning" to "صباح الخير",
            "good evening" to "مساء الخير",
            "thank you" to "شكرا لك",
            "how are you" to "كيف حالك",
            "please" to "من فضلك",
            "where are you" to "أين أنت",
            "what is your name" to "ما اسمك",
            "bonjour" to "مرحبا",
            "bonsoir" to "مساء الخير",
            "merci" to "شكرا",
            "comment allez vous" to "كيف حالك",
            "je ne comprends pas" to "أنا لا أفهم",
            "s'il vous plaît" to "من فضلك"
        )
        phrases[normalized]?.let { return it }

        val words = mapOf(
            "hello" to "مرحبا", "hi" to "مرحبا", "yes" to "نعم", "no" to "لا",
            "please" to "من فضلك", "thanks" to "شكرا", "thank" to "شكرا",
            "you" to "أنت", "i" to "أنا", "we" to "نحن", "good" to "جيد",
            "morning" to "صباح", "evening" to "مساء", "today" to "اليوم",
            "tomorrow" to "غدا", "help" to "مساعدة", "name" to "اسم",
            "phone" to "هاتف", "email" to "بريد إلكتروني", "account" to "حساب",
            "open" to "افتح", "settings" to "الإعدادات", "file" to "ملف",
            "files" to "ملفات", "delete" to "احذف", "save" to "احفظ",
            "water" to "ماء", "food" to "طعام", "house" to "منزل",
            "bonjour" to "مرحبا", "salut" to "مرحبا", "merci" to "شكرا",
            "oui" to "نعم", "non" to "لا", "vous" to "أنت", "je" to "أنا",
            "nous" to "نحن", "bonjour" to "مرحبا", "aujourd'hui" to "اليوم",
            "demain" to "غدا", "aide" to "مساعدة", "nom" to "اسم",
            "téléphone" to "هاتف", "compte" to "حساب", "ouvrir" to "افتح",
            "fichier" to "ملف", "supprimer" to "احذف", "enregistrer" to "احفظ",
            "eau" to "ماء", "maison" to "منزل", "ça" to "هذا", "va" to "يسير",
            "bien" to "بخير", "comment" to "كيف", "what" to "ماذا",
            "where" to "أين", "are" to "يكون", "is" to "هو", "my" to "لي"
        )
        val tokens = text.split(Regex("(?<=\\s)|(?=\\s)"))
        var translated = false
        val result = tokens.joinToString("") { token ->
            val key = normalize(token)
            val replacement = words[key]
            if (replacement != null) {
                translated = true
                replacement
            } else {
                token
            }
        }
        return if (translated) result else text
    }

    companion object {
        fun normalize(value: String): String = Normalizer.normalize(value.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace('أ', 'ا')
            .replace('إ', 'ا')
            .replace('آ', 'ا')
            .replace('ى', 'ي')
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")

        private fun similarity(left: String, right: String): Double {
            if (left.isEmpty() || right.isEmpty()) return 0.0
            val previous = IntArray(right.length + 1) { it }
            for (i in left.indices) {
                var diagonal = previous[0]
                previous[0] = i + 1
                for (j in right.indices) {
                    val above = previous[j + 1]
                    previous[j + 1] = minOf(
                        previous[j + 1] + 1,
                        previous[j] + 1,
                        diagonal + if (left[i] == right[j]) 0 else 1
                    )
                    diagonal = above
                }
            }
            return 1.0 - previous[right.length].toDouble() / max(left.length, right.length)
        }
    }
}
