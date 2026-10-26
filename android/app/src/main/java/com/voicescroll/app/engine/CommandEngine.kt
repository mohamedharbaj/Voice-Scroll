package com.voicescroll.app.engine

import com.voicescroll.app.model.CommandType

/**
 * Centralized, offline Command Engine.
 * Converts spoken phrases in Modern Standard Arabic, Moroccan Darija, French, and English
 * into unified CommandType actions. Supports custom user aliases without any remote server or AI.
 */
object CommandEngine {

    // Centralized built-in dictionary
    private val DEFAULT_ALIASES: Map<CommandType, List<String>> = mapOf(
        CommandType.NEXT to listOf(
            // Arabic Standard & Darija - Next / Swipe Up
            "طلع",
            "دوز",
            "التالي",
            "طالع",
            "اطلع",
            "إطلع",
            "طّلع",
            "ططلع",
            "دووز",
            "دوزو",
            "دوزي",
            "دوزها",
            "طلعها",
            "ندوز",
            "نطلع",
            "دوزلي",
            "طلعلي",
            "دوزوا",
            "طلعوا",
            "سير",
            "مرر",
            "مرّر",
            "مرر للاعلي",
            "مرر للأعلى",
            "الفيديو التالي",
            "التالي من فضلك",
            "دوز للفيديو الجاي",
            "طلع للفوق",
            "طلع لفوق",
            "طلع الفيديو",
            "الفيديو الجاي",
            "دوز للي بعد",
            "دوز للفيديو اللي بعدو",
            "زيد",
            "فوق",
            "للفوق",
            "دوس",
            "دوزز",
            "تلع",
            "الفيديو لي بعد",
            "فيديو جديد",
            // Darija written in Latin letters (what en/fr recognizers usually output)
            "doz",
            "dooz",
            "dawz",
            "douz",
            "dowz",
            "dwz",
            "doze",
            "dos",
            "tla3",
            "tla",
            "tl3",
            "tlaa",
            "tlla3",
            "tal3",
            "tla3 lfo9",
            "dwzha",
            "tla3ha",
            "zid",
            "fo9",
            "lfo9",
            "fouq",
            // English / French words as heard by the Arabic recognizer
            "نكست",
            "نيكست",
            "نكسط",
            "نيكست فيديو",
            "اب",
            "سكرول",
            "سكرول اب",
            "سوايب اب",
            "سويفان",
            "سوفان",
            "سويفون",
            "سوفون",
            "سيفان",
            "مونت",
            "مون",
            // French
            "suivant",
            "suivante",
            "passe",
            "fais defiler",
            "fais défiler",
            "video suivante",
            "vidéo suivante",
            "passe a la suivante",
            "passe à la suivante",
            "vers le haut",
            "monte",
            "suivante video",
            "video d apres",
            "plus haut",
            "haut",
            // English
            "next",
            "next video",
            "scroll",
            "scroll up",
            "swipe up",
            "up",
            "next one",
            "next please",
            "please next",
            "go next",
            "go up",
            "skip",
            "skip video",
            "next clip"
        ),

        CommandType.PREVIOUS to listOf(
            // Arabic Standard & Darija - Previous / Swipe Down
            "هبط",
            "رجع",
            "ارجع",
            "إرجع",
            "هابط",
            "اهبط",
            "إهبط",
            "هبّط",
            "هود",
            "رجّع",
            "رجعو",
            "رجعي",
            "رجعها",
            "هبطها",
            "نرجع",
            "نهبط",
            "رجعلي",
            "هبطلي",
            "رجعوا",
            "هبطوا",
            "السابق",
            "رجوع",
            "الفيديو السابق",
            "مرر للاسفل",
            "مرر للأسفل",
            "ارجع للفيديو السابق",
            "هبط لتحت",
            "هبط دابا",
            "رجع للفيديو اللي فات",
            "الفيديو اللي فات",
            "الفيديو لي فات",
            "تحت",
            "لتحت",
            "هبت",
            "هبطط",
            "الفيديو لي قبل",
            "الفيديو اللي قبل",
            "الفيديو القبلي",
            "لور",
            // Darija written in Latin letters
            "hbat",
            "hbet",
            "hbt",
            "hbit",
            "habat",
            "habbat",
            "hbat lte7t",
            "lte7t",
            "t7t",
            "rjaa",
            "rja3",
            "rje3",
            "rj3",
            "rjaa3",
            "rjaa3 lwra",
            "raje3",
            "raja3",
            "rjjaa",
            "lwra",
            "lor",
            // English / French words as heard by the Arabic recognizer
            "بريفيوس",
            "بريفيوز",
            "بريفيس",
            "داون",
            "باك",
            "جو باك",
            "سكرول داون",
            "سوايب داون",
            "بريسيدون",
            "بريسيدان",
            "بريسيدو",
            "ريتور",
            "ريتورن",
            "ديسان",
            "ديسون",
            // French
            "precedent",
            "précédent",
            "precedente",
            "précédente",
            "retour",
            "reviens",
            "video precedente",
            "vidéo précédente",
            "vers le bas",
            "descends",
            "descend",
            "revenir",
            "retourne",
            "plus bas",
            "bas",
            "video d avant",
            // English
            "previous",
            "previous video",
            "back",
            "go back",
            "scroll down",
            "swipe down",
            "down",
            "last video",
            "previous one",
            "go down",
            "rewind"
        ),

        CommandType.STOP to listOf(
            // Arabic Standard & Darija - Stop
            "وقف",
            "حبس",
            "صافي",
            "توقف",
            "اوقف",
            "أوقف",
            "وقّف",
            "وقفو",
            "وقفي",
            "وقفها",
            "حبّس",
            "حبسو",
            "حبسي",
            "حبسها",
            "اوكف",
            "بركة",
            "توقف عن الاستماع",
            "وقف الاستماع",
            "ستوب",
            "ستوب ليسنينج",
            "وقف ليسنينج",
            "كفى",
            "يكفي",
            "wqf",
            "wakaf",
            "wa9ef",
            "wa9f",
            "waqef",
            "wkf",
            "wa9ef daba",
            "7bs",
            "hbes",
            "hbess",
            "habs",
            "hbs",
            "safi",
            "saafi",
            "barka",
            // French
            "stop",
            "arrete",
            "arrête",
            "arreter",
            "arrêter",
            "arrete d ecouter",
            "arrête d'écouter",
            "stoppe",
            // English
            "stop",
            "stop listening",
            "halt",
            "stop it",
            "quit",
            "enough"
        ),

        CommandType.RESUME to listOf(
            // Arabic Standard & Darija - Resume
            "كمل",
            "كمّل",
            "تابع",
            "استمر",
            "ابدا",
            "ابدأ",
            "استئناف",
            "عاود خدم",
            "بدا",
            "كملها",
            "كملو",
            "عاود",
            "عاود بدا",
            "ريزوم",
            "ريزيوم",
            "كونتينيو",
            "ستارت",
            "kml",
            "kemmel",
            "kamel",
            "kmel",
            "kemel",
            "bda",
            "3awd",
            "3awd khdem",
            "tabi3",
            "tabe3",
            // French
            "continue",
            "continuer",
            "reprendre",
            "recommence",
            "recommencer",
            "redemarre",
            "redémarre",
            "commence",
            "demarre",
            "démarre",
            // English
            "resume",
            "continue",
            "start",
            "start listening",
            "keep going",
            "go on",
            "begin"
        ),

        CommandType.PAUSE to listOf(
            "ايقاف مؤقت",
            "إيقاف مؤقت",
            "وقف الفيديو",
            "حبس الفيديو",
            "وقف مؤقت",
            "بوز",
            "باوز",
            "بوزا",
            "pause",
            "pause video",
            "pause it",
            "paws",
            "pauza",
            "mets en pause",
            "mettre en pause",
            "pause la video",
            "mise en pause"
        ),

        CommandType.PLAY to listOf(
            "شغل",
            "تشغيل",
            "خدم",
            "شغلو",
            "شغلها",
            "خدمها",
            "شغل الفيديو",
            "بلاي",
            "chghal",
            "chghel",
            "shghal",
            "chaghal",
            "khdem",
            "play",
            "play video",
            "play it",
            "lance",
            "lance la video",
            "joue",
            "jouer",
            "lecture"
        )
    )

    // Pre-normalized internal lookup cache for ultra-fast matching
    private val normalizedDefaultMap: MutableMap<String, CommandType> = HashMap()
    private val sortedMultiWordPhrases: List<Pair<String, CommandType>>

    // Utterances longer than this are probably video audio / conversation, not a command
    private const val MAX_WORDS_FOR_TOKEN_MATCH = 5
    private const val MAX_WORDS_FOR_PHRASE_MATCH = 8

    init {
        val multi = mutableListOf<Pair<String, CommandType>>()
        for ((cmd, aliases) in DEFAULT_ALIASES) {
            for (alias in aliases) {
                val norm = ArabicNormalizer.normalize(alias)
                if (norm.isEmpty()) continue
                // first definition wins when the same word appears under two commands
                if (!normalizedDefaultMap.containsKey(norm)) {
                    normalizedDefaultMap[norm] = cmd
                }
                if (norm.contains(' ')) multi.add(norm to cmd)
            }
        }
        // Longest (most specific) phrases first
        sortedMultiWordPhrases = multi.sortedByDescending { it.first.length }
    }

    /**
     * Resolves spoken raw speech into a CommandType, regardless of the language the speech
     * recognizer used to transcribe it (Arabic script, Latin script, Darija, MSA, French, English).
     *
     * Order of precedence:
     * 1. Custom user aliases (exact, or whole-word containment)
     * 2. Exact normalized match in the built-in dictionary
     * 3. Multi-word phrase containment, whole words only (longest phrase first)
     * 4. Single-word token match with Darija/Arabic prefix/suffix stripping
     */
    fun match(
        rawText: String?,
        customAliases: Map<String, CommandType> = emptyMap()
    ): CommandMatchResult {
        if (rawText.isNullOrBlank()) {
            return CommandMatchResult(CommandType.NONE, "", rawText ?: "")
        }

        val normalized = ArabicNormalizer.normalize(rawText)
        if (normalized.isEmpty()) {
            return CommandMatchResult(CommandType.NONE, "", rawText)
        }
        val padded = " $normalized "

        // 1. Custom user aliases
        for ((customWord, cmd) in customAliases) {
            val normCustom = ArabicNormalizer.normalize(customWord)
            if (normCustom.isNotEmpty()) {
                if (normalized == normCustom || padded.contains(" $normCustom ")) {
                    return CommandMatchResult(cmd, customWord, rawText)
                }
            }
        }

        // 2. Exact match in built-in dictionary
        normalizedDefaultMap[normalized]?.let { cmd ->
            return CommandMatchResult(cmd, normalized, rawText)
        }

        val words = normalized.split(" ").filter { it.isNotBlank() }

        // 3. Multi-word phrases ("الفيديو التالي", "scroll down", "mets en pause"...), whole words only
        if (words.size <= MAX_WORDS_FOR_PHRASE_MATCH) {
            for ((phrase, cmd) in sortedMultiWordPhrases) {
                if (padded.contains(" $phrase ")) {
                    return CommandMatchResult(cmd, phrase, rawText)
                }
            }
        }

        // 4. Token-based match. Latest spoken word wins.
        if (words.size <= MAX_WORDS_FOR_TOKEN_MATCH) {
            for (word in words.reversed()) {
                matchSingleWord(word)?.let { (cmd, alias) ->
                    return CommandMatchResult(cmd, alias, rawText)
                }
            }
        }

        return CommandMatchResult(CommandType.NONE, "", rawText)
    }

    private fun matchSingleWord(word: String): Pair<CommandType, String>? {
        if (word.length < 2) return null

        normalizedDefaultMap[word]?.let { return it to word }

        val isArabic = word.any { it in '\u0600'..'\u06FF' }

        if (isArabic) {
            // Arabic definite article / conjunction: "والتالي", "فدوز"
            // Verb prefixes (Darija): "ندوز", "نطلع", "نرجع", "يطلع", "تهبط"
            if (word.length >= 3 && word[0] in "وفنتيب") {
                val stripped = word.substring(1)
                normalizedDefaultMap[stripped]?.let { return it to stripped }
                // double prefix: "وندوز", "وكتدوز" style (و + ن/ت/ي)
                if (stripped.length >= 3 && stripped[0] in "نتيك") {
                    val stripped2 = stripped.substring(1)
                    normalizedDefaultMap[stripped2]?.let { return it to stripped2 }
                }
            }
            // "كت" present-tense marker in Darija: "كتدوز", "كيطلع"
            if (word.length >= 4 && (word.startsWith("كت") || word.startsWith("كي") || word.startsWith("غا"))) {
                val stripped = word.substring(2)
                normalizedDefaultMap[stripped]?.let { return it to stripped }
            }
            // Suffixes: "دوزها", "طلعلي", "رجعو", "هبطوا", "طلعني", "دوزلنا"
            for (suffix in ARABIC_SUFFIXES) {
                if (word.length >= suffix.length + 2 && word.endsWith(suffix)) {
                    val stripped = word.substring(0, word.length - suffix.length)
                    normalizedDefaultMap[stripped]?.let { return it to stripped }
                }
            }
        }

        // Repeated/elongated characters: "دوووز" -> "دوز", "nexxxt" -> "next"
        val collapsed = word.replace(Regex("(.)\\1+"), "$1")
        if (collapsed != word && collapsed.length >= 2) {
            normalizedDefaultMap[collapsed]?.let { return it to collapsed }
        }
        return null
    }

    private val ARABIC_SUFFIXES = listOf("ها", "لي", "لو", "هم", "نا", "ني", "ك", "و", "وا", "ي")

    /**
     * Returns all default aliases for documentation and UI display.
     */
    fun getDefaultAliases(): Map<CommandType, List<String>> = DEFAULT_ALIASES
}

data class CommandMatchResult(
    val command: CommandType,
    val matchedAlias: String,
    val rawText: String
) {
    val isValid: Boolean
        get() = command != CommandType.NONE
}
