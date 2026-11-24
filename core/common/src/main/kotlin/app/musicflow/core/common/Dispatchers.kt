package app.musicflow.core.common

import javax.inject.Qualifier

@Qualifier @Retention(AnnotationRetention.BINARY) annotation class IoDispatcher
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class DefaultDispatcher
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class ApplicationScope

fun interface Clock { fun now(): Long
    companion object { val System = Clock { java.lang.System.currentTimeMillis() } }
}
