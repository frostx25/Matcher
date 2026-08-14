package shop.vibeali.app.domain.profile

import android.content.Context
import androidx.core.content.edit
import java.time.Year

data class LocalProfile(
    val displayName: String,
    val birthYear: Int,
    val bio: String,
    val intent: String,
) {
    val age: Int
        get() = Year.now().value - birthYear
}

object AgeGate {
    fun isAdult(birthYear: Int, currentYear: Int = Year.now().value): Boolean {
        return birthYear in 1900..(currentYear - 18)
    }
}

/** Local-only storage for the prototype. Production will use the authenticated profile API. */
class LocalProfileStore(context: Context) {
    companion object {
        const val PREFS_NAME = "matcher_local_profile"

        private const val DISPLAY_NAME = "display_name"
        private const val BIRTH_YEAR = "birth_year"
        private const val BIO = "bio"
        private const val INTENT = "intent"
    }

    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): LocalProfile? {
        val displayName = preferences.getString(DISPLAY_NAME, null)
        val birthYear = preferences.getInt(BIRTH_YEAR, -1)
        if (displayName.isNullOrBlank() || !AgeGate.isAdult(birthYear)) {
            return null
        }

        return LocalProfile(
            displayName = displayName,
            birthYear = birthYear,
            bio = preferences.getString(BIO, "").orEmpty(),
            intent = preferences.getString(INTENT, "Conhecer pessoas").orEmpty(),
        )
    }

    fun save(profile: LocalProfile) {
        preferences.edit {
            putString(DISPLAY_NAME, profile.displayName.trim())
            putInt(BIRTH_YEAR, profile.birthYear)
            putString(BIO, profile.bio.trim())
            putString(INTENT, profile.intent.trim())
        }
    }
}
