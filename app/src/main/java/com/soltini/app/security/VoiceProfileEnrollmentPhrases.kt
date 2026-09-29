package com.soltini.app.security

/**
 * VoiceProfileEnrollmentPhrases
 *
 * Provides at least 10 phonetically diverse Hindi, Hinglish, and English sentences
 * designed to calibrate pitch, formant transitions, vowel spaces, and speech cadence
 * for speaker recognition and acoustic embedding generation.
 */
object VoiceProfileEnrollmentPhrases {

    data class EnrollmentPhrase(
        val step: Int,
        val phrase: String,
        val language: String,
        val guidance: String
    )

    val PHRASES = listOf(
        EnrollmentPhrase(
            step = 1,
            phrase = "Namaste Myra, mera naam aur meri aawaz pehchano.",
            language = "Hindi / Hinglish",
            guidance = "Normal tone me clearly bolein"
        ),
        EnrollmentPhrase(
            step = 2,
            phrase = "Aaj ka mausam kaisa hai aur Delhi me baarish hogi kya?",
            language = "Hindi",
            guidance = "Natural sawal puchhne ke andaaz me bolein"
        ),
        EnrollmentPhrase(
            step = 3,
            phrase = "Mere schedule me subah aath baje reminder set kar do.",
            language = "Hinglish",
            guidance = "Numbers aur time clarity ke sath bolein"
        ),
        EnrollmentPhrase(
            step = 4,
            phrase = "Arijit Singh ke naye gaane playlist me play karo.",
            language = "Hinglish",
            guidance = "Casual listening tone me bolein"
        ),
        EnrollmentPhrase(
            step = 5,
            phrase = "Mera personal assistant Myra hai aur ye meri aawaz secure rakhta hai.",
            language = "Hinglish",
            guidance = "Clear pronunciation ke sath bolein"
        ),
        EnrollmentPhrase(
            step = 6,
            phrase = "Quick message send karo ki main das minute me pahunch raha hoon.",
            language = "Hinglish",
            guidance = "Speed aur conversational flow maintain karein"
        ),
        EnrollmentPhrase(
            step = 7,
            phrase = "Open calculator and calculate total expenses for this month.",
            language = "English",
            guidance = "Fluent English pronunciation me bolein"
        ),
        EnrollmentPhrase(
            step = 8,
            phrase = "Smart home light band karo aur bedroom fan chala do.",
            language = "Hindi / Hinglish",
            guidance = "IoT command voice tone me bolein"
        ),
        EnrollmentPhrase(
            step = 9,
            phrase = "Mera phone aur meri settings sirf meri permission se chalegi.",
            language = "Hinglish",
            guidance = "Firm confident tone me bolein"
        ),
        EnrollmentPhrase(
            step = 10,
            phrase = "Thank you Myra, system security check complete karo.",
            language = "Hinglish",
            guidance = "Aakhri sample complete karein"
        )
    )

    fun getPhraseForStep(step: Int): EnrollmentPhrase {
        val index = (step - 1).coerceIn(0, PHRASES.size - 1)
        return PHRASES[index]
    }

    val REQUIRED_SAMPLES_COUNT: Int = PHRASES.size
}
