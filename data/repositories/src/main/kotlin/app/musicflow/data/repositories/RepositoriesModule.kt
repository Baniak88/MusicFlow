package app.musicflow.data.repositories

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** All repositories use constructor injection; this module only anchors the component. */
@Module
@InstallIn(SingletonComponent::class)
object RepositoriesModule
