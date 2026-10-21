package ca.dynamicsolutions.sonarbridge

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import android.text.format.DateFormat
import java.text.SimpleDateFormat

/** Regional formatting is independent of app wording and never changes the phone locale. */
object RegionalFormat {
    /** Respect the device's 12/24-hour setting while choosing punctuation and digits regionally. */
    fun timeFormatter(context: Context): java.text.DateFormat {
        val regional = AppLanguage.regionalLocale(context)
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocales(LocaleList(regional))
        val regionalContext = context.createConfigurationContext(configuration)
        val skeleton = if (DateFormat.is24HourFormat(regionalContext)) "Hms" else "hms"
        val pattern = DateFormat.getBestDateTimePattern(regional, skeleton)
        return SimpleDateFormat(pattern, regional)
    }
}
