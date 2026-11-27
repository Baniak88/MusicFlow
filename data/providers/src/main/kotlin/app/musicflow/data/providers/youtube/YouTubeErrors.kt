package app.musicflow.data.providers.youtube

import app.musicflow.core.common.defaultClassify
import app.musicflow.core.model.ProviderError
import app.musicflow.core.model.ProviderError.Restricted.Reason
import org.schabi.newpipe.extractor.exceptions.AccountTerminatedException
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ContentNotSupportedException
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException
import org.schabi.newpipe.extractor.exceptions.HttpResponseException
import org.schabi.newpipe.extractor.exceptions.PaidContentException
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.exceptions.PrivateContentException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException
import org.schabi.newpipe.extractor.exceptions.YoutubeMusicPremiumContentException

/** Maps NewPipeExtractor exceptions to MusicFlow's [ProviderError] taxonomy. */
object YouTubeErrors {
    fun classify(t: Throwable): ProviderError = when (t) {
        is ProviderError -> t
        is ReCaptchaException -> ProviderError.RateLimited("YouTube asked for a CAPTCHA / rate limited this network", t)
        is SignInConfirmNotBotException -> ProviderError.RateLimited("YouTube temporarily blocked anonymous access from this IP", t, botCheck = true)
        is AgeRestrictedContentException -> ProviderError.Restricted(Reason.AGE, "Age-restricted content requires sign-in", t)
        is GeographicRestrictionException -> ProviderError.Restricted(Reason.GEOGRAPHIC, "Not available in your country", t)
        is org.schabi.newpipe.extractor.exceptions.SoundCloudGoPlusContentException ->
            ProviderError.Restricted(Reason.PAID, "SoundCloud Go+ track: only a 30-second preview is available", t)
        is YoutubeMusicPremiumContentException -> ProviderError.Restricted(Reason.PAID, "Only available with YouTube Music Premium", t)
        is PaidContentException -> ProviderError.Restricted(Reason.PAID, "Paid or members-only content", t)
        is PrivateContentException -> ProviderError.Unavailable("This track is private", t)
        is AccountTerminatedException -> ProviderError.Unavailable("The uploader's account was terminated", t)
        is ContentNotSupportedException -> ProviderError.NotSupported(t.message ?: "Content not supported")
        is ContentNotAvailableException -> ProviderError.Unavailable(t.message ?: "Content not available", t)
        is HttpResponseException -> if (t.message?.contains("5") == true) ProviderError.Network(t.message ?: "HTTP error", t) else ProviderError.Unknown(t.message ?: "HTTP error", t)
        is ParsingException -> {
            val cause = t.cause
            if (cause is java.io.IOException) defaultClassify(cause) else ProviderError.Parsing("YouTube response format changed: ${t.message}", t)
        }
        else -> {
            val cause = t.cause
            if (cause != null && cause !== t && cause !is RuntimeException) classify(cause) else defaultClassify(t)
        }
    }
}
