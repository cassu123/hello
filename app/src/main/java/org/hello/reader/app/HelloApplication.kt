package org.hello.reader.app

import android.app.Application
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.CreationExtras
import coil.ImageLoader
import coil.ImageLoaderFactory
import org.hello.reader.feature.widget.ContinueReadingWidgetUpdater

class HelloApplication : Application(), ImageLoaderFactory {
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        ContinueReadingWidgetUpdater.initialize(
            context = this,
            scope = container.applicationScope,
            vaultProvider = { container.vault },
            playbackSessionProvider = { container.playbackSession },
        )
    }

    /**
     * Covers are served by the user's Storyteller server and require its Bearer
     * token, so Coil shares the app's OkHttpClient (and its ServerAuthInterceptor).
     * The interceptor only attaches the token for URLs within the active server's scope.
     */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient { container.okHttpClient }
        .crossfade(true)
        .build()
}

/** Resolves the [AppContainer] inside a `viewModelFactory { initializer { ... } }` block. */
val CreationExtras.appContainer: AppContainer
    get() = (checkNotNull(this[APPLICATION_KEY]) as HelloApplication).container
