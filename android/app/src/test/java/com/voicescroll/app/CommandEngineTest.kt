package com.voicescroll.app

import com.voicescroll.app.engine.ArabicNormalizer
import com.voicescroll.app.engine.CommandEngine
import com.voicescroll.app.model.CommandType
import org.junit.Assert.assertEquals
import org.junit.Test

class CommandEngineTest {

    @Test
    fun testNextCommands() {
        val nextPhrases = listOf(
            "طلع",
            "دوز",
            "next",
            "التالي",
            "طلع للفوق",
            "طلع لفوق",
            "دوز للفيديو الجاي",
            "دوز للي بعد",
            "فوق",
            "up",
            "scroll up",
            "swipe up",
            "suivant",
            "monte"
        )
        for (phrase in nextPhrases) {
            val result = CommandEngine.match(phrase)
            assertEquals("Phrase '$phrase' should match NEXT", CommandType.NEXT, result.command)
        }
    }

    @Test
    fun testPreviousCommands() {
        val prevPhrases = listOf(
            "هبط",
            "رجع",
            "ارجع",
            "رجوع",
            "previous",
            "back",
            "السابق",
            "هبط لتحت",
            "تحت",
            "down",
            "scroll down",
            "swipe down",
            "precedent",
            "descends"
        )
        for (phrase in prevPhrases) {
            val result = CommandEngine.match(phrase)
            assertEquals("Phrase '$phrase' should match PREVIOUS", CommandType.PREVIOUS, result.command)
        }
    }

    @Test
    fun testStopWordsPauseTheVideo() {
        val stopPhrases = listOf(
            "وقف",
            "حبس",
            "صافي",
            "توقف",
            "بركة",
            "stop",
            "arrete"
        )
        for (phrase in stopPhrases) {
            val result = CommandEngine.match(phrase)
            assertEquals("Phrase '$phrase' should match PAUSE (stop video)", CommandType.PAUSE, result.command)
        }
    }

    @Test
    fun testStopListeningNeedsExplicitPhrase() {
        // الكلمات العادية = إيقاف الفيديو فقط (ماشي إيقاف التطبيق)
        assertEquals(CommandType.PAUSE, CommandEngine.match("stop").command)
        assertEquals(CommandType.PAUSE, CommandEngine.match("وقف").command)
        assertEquals(CommandType.PAUSE, CommandEngine.match("قف").command)
        assertEquals(CommandType.PAUSE, CommandEngine.match("وقف الفيديو").command)
        // إيقاف التطبيق = فقط بعبارة صريحة
        assertEquals(CommandType.STOP, CommandEngine.match("stop listening").command)
        assertEquals(CommandType.STOP, CommandEngine.match("وقف الاستماع").command)
        assertEquals(CommandType.STOP, CommandEngine.match("arrête d'écouter").command)
    }

    @Test
    fun testNaturalSpeechWithFillerWords() {
        assertEquals(CommandType.NEXT, CommandEngine.match("عفاك دوز").command)
        assertEquals(CommandType.NEXT, CommandEngine.match("دوز دابا").command)
        assertEquals(CommandType.NEXT, CommandEngine.match("طلع دابا").command)
        assertEquals(CommandType.NEXT, CommandEngine.match("please next").command)

        assertEquals(CommandType.PREVIOUS, CommandEngine.match("رجع دابا").command)
        assertEquals(CommandType.PREVIOUS, CommandEngine.match("هبط عفاك").command)
        assertEquals(CommandType.PREVIOUS, CommandEngine.match("go back please").command)

        assertEquals(CommandType.PAUSE, CommandEngine.match("صافي وقف").command)
        assertEquals(CommandType.PAUSE, CommandEngine.match("حبس دابا").command)
    }

    @Test
    fun testSpeechWithBackgroundVideoAudio() {
        // Words with conjunction prefixes ('و', 'ف')
        assertEquals(CommandType.NEXT, CommandEngine.match("ودوز").command)
        assertEquals(CommandType.NEXT, CommandEngine.match("فدوز").command)
        assertEquals(CommandType.NEXT, CommandEngine.match("وطلع").command)
        assertEquals(CommandType.PREVIOUS, CommandEngine.match("وهبط").command)
        assertEquals(CommandType.PREVIOUS, CommandEngine.match("ورجع").command)
        assertEquals(CommandType.PAUSE, CommandEngine.match("ووقف").command)

        // Elongated words when speaking loudly over media audio
        assertEquals(CommandType.NEXT, CommandEngine.match("دووووز").command)
        assertEquals(CommandType.NEXT, CommandEngine.match("ططلع").command)
        assertEquals(CommandType.PAUSE, CommandEngine.match("صااافي").command)

        // Command spoken inside video dialogue sentence
        assertEquals(CommandType.NEXT, CommandEngine.match("شوف هاد الفيديو دوز دابا").command)
        assertEquals(CommandType.PREVIOUS, CommandEngine.match("هاد اللقطة زوينة رجع").command)
    }

    @Test
    fun testCoreCommandsExplicitly() {
        // Issue 4: "طلع", "هبط", "دوز", "رجع" and "وقف"
        assertEquals(CommandType.NEXT, CommandEngine.match("طلع").command)
        assertEquals(CommandType.NEXT, CommandEngine.match("طالع").command)
        assertEquals(CommandType.NEXT, CommandEngine.match("اطلع").command)
        assertEquals(CommandType.NEXT, CommandEngine.match("دوز").command)
        assertEquals(CommandType.NEXT, CommandEngine.match("دووز").command)
        assertEquals(CommandType.NEXT, CommandEngine.match("سير").command)

        assertEquals(CommandType.PREVIOUS, CommandEngine.match("هبط").command)
        assertEquals(CommandType.PREVIOUS, CommandEngine.match("هابط").command)
        assertEquals(CommandType.PREVIOUS, CommandEngine.match("اهبط").command)
        assertEquals(CommandType.PREVIOUS, CommandEngine.match("رجع").command)
        assertEquals(CommandType.PREVIOUS, CommandEngine.match("ارجع").command)
        assertEquals(CommandType.PREVIOUS, CommandEngine.match("رجّع").command)

        assertEquals(CommandType.PAUSE, CommandEngine.match("وقف").command)
        assertEquals(CommandType.PAUSE, CommandEngine.match("وقّف").command)
        assertEquals(CommandType.PAUSE, CommandEngine.match("حبس").command)
        assertEquals(CommandType.PAUSE, CommandEngine.match("صافي").command)
    }

    @Test
    fun testArabicNormalizer() {
        assertEquals("طلع", ArabicNormalizer.normalize("طَلَعَ"))
        assertEquals("هبط", ArabicNormalizer.normalize("هَبِّطْ"))
        assertEquals("ارجع", ArabicNormalizer.normalize("إِرْجَعْ"))
        assertEquals("next", ArabicNormalizer.normalize("  Next!  "))
    }

    @Test
    fun testAllLanguagesAndScripts() {
        val cases = mapOf(
            // Latin-script Darija (as produced by en/fr recognizers)
            "dawz" to CommandType.NEXT,
            "tla3" to CommandType.NEXT,
            "hbat" to CommandType.PREVIOUS,
            "rje3" to CommandType.PREVIOUS,
            "wa9ef" to CommandType.PAUSE,
            "7bs" to CommandType.PAUSE,
            "kml" to CommandType.RESUME,
            // English/French as written in Arabic script
            "نكست" to CommandType.NEXT,
            "بريفيوس" to CommandType.PREVIOUS,
            "ستوب" to CommandType.PAUSE,
            "ريزوم" to CommandType.RESUME,
            "بوز" to CommandType.PAUSE,
            // Accents are ignored
            "précédent" to CommandType.PREVIOUS,
            "vidéo suivante" to CommandType.NEXT,
            // Filler words around the command
            "please next" to CommandType.NEXT,
            "عفاك دوز" to CommandType.NEXT,
            "mets en pause" to CommandType.PAUSE,
            "كتدوز" to CommandType.NEXT
        )
        for ((phrase, expected) in cases) {
            assertEquals("Phrase '$phrase'", expected, CommandEngine.match(phrase).command)
        }
    }

    @Test
    fun testNoFalsePositivesInsideWords() {
        val none = listOf("cup of coffee", "support", "nonstop music", "background")
        for (phrase in none) {
            assertEquals("Phrase '$phrase' must not match", CommandType.NONE, CommandEngine.match(phrase).command)
        }
    }

    @Test
    fun testLongSpeechIsIgnored() {
        val longSpeech = "so yesterday I went to the market and then I saw him go up the street"
        assertEquals(CommandType.NONE, CommandEngine.match(longSpeech).command)
    }
}
