package app.musicflow.data.providers.newpipe

import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import java.util.Locale

/**
 * NewPipeExtractor keeps its downloader and localisation in global state. Every provider built on
 * it (YouTube Music, SoundCloud) initialises it through here, exactly once, so they never
 * overwrite each other's setup.
 */
object NewPipeRuntime {
    @Volatile private var initialized = false

    fun ensure(downloader: Downloader, locale: Locale, countryOverride: String?) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val country = countryOverride?.takeIf { it.length == 2 } ?: locale.country.takeIf { it.length == 2 } ?: "US"
            NewPipe.init(downloader, Localization.fromLocale(locale), ContentCountry(country))
            initialized = true
        }
    }
}
