package org.hello.reader.app

import android.content.Context
import org.hello.reader.BuildConfig
import org.hello.reader.core.data.AnnotationRepository
import org.hello.reader.core.data.BookRepository
import org.hello.reader.core.data.ReaderPreferencesRepository
import org.hello.reader.core.data.ServerConnectionRepository
import org.hello.reader.core.database.HelloDatabase
import org.hello.reader.core.engine.audio.Media3AudioPlaybackController
import org.hello.reader.core.engine.audio.PlaybackSession
import org.hello.reader.core.engine.reader.ReaderStateMachine
import org.hello.reader.core.network.DictionaryService
import org.hello.reader.core.network.NetworkModule
import org.hello.reader.core.network.ServerAuthInterceptor
import org.hello.reader.core.network.StorytellerApi
import org.hello.reader.core.network.VaultBaseUrlProvider
import org.hello.reader.core.security.SecurityVault
import org.hello.reader.core.security.SecurityVaultImpl
import org.hello.reader.feature.library.BookImportController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.OkHttpClient

/**
 * Manual dependency graph. Every member is lazy: [vault] performs Android
 * Keystore I/O on first access, so touch it from a background dispatcher first
 * (see [MainViewModel]).
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    /** Process-lifetime scope for writes that must finish after a screen goes away. */
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val vault: SecurityVault by lazy {
        SecurityVaultImpl(appContext)
    }

    val database: HelloDatabase by lazy {
        HelloDatabase.build(appContext)
    }

    private val _authExpired = MutableSharedFlow<String>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Emits a profile id whenever the server rejects that profile's token with 401. */
    val authExpired: SharedFlow<String> = _authExpired.asSharedFlow()

    val okHttpClient: OkHttpClient by lazy {
        NetworkModule.okHttpClient(
            authInterceptor = ServerAuthInterceptor(VaultBaseUrlProvider(vault)) { profileId ->
                _authExpired.tryEmit(profileId)
            },
            enableLogging = BuildConfig.DEBUG,
        )
    }

    val storytellerApi: StorytellerApi by lazy { NetworkModule.storytellerApi(okHttpClient) }

    val serverConnectionRepository: ServerConnectionRepository by lazy {
        ServerConnectionRepository(storytellerApi, vault)
    }

    val bookRepository: BookRepository by lazy {
        BookRepository(
            api = storytellerApi,
            bookDao = database.bookDao(),
            checkpointDao = database.syncCheckpointDao(),
            vault = vault,
            seriesDao = database.seriesDao(),
            collectionDao = database.collectionDao(),
            database = database,
            filesDir = appContext.filesDir,
            onCheckpointSaved = {
                org.hello.reader.feature.widget.ContinueReadingWidgetUpdater.requestUpdate(appContext)
            },
        )
    }

    val readerPreferencesRepository: ReaderPreferencesRepository by lazy {
        ReaderPreferencesRepository(appContext)
    }

    val readerStateMachine: ReaderStateMachine by lazy {
        ReaderStateMachine()
    }

    val annotationRepository: AnnotationRepository by lazy {
        AnnotationRepository(
            annotationDao = database.annotationDao(),
            vault = vault,
        )
    }

    /** Narration playback that outlives the reader screen (mini player, background audio). */
    val playbackSession: PlaybackSession by lazy {
        PlaybackSession(
            controllerFactory = { Media3AudioPlaybackController(appContext) },
            saveCheckpoint = { bookRepository.saveCheckpoint(it) },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        )
    }

    val dictionaryService: DictionaryService by lazy {
        DictionaryService()
    }

    val localBookImporter: org.hello.reader.core.data.LocalBookImporter by lazy {
        org.hello.reader.core.data.LocalBookImporter(appContext, database.bookDao())
    }

    /** Shared by Library and Settings; imports outlive the screen that started them. */
    val bookImports: BookImportController<android.net.Uri> by lazy {
        BookImportController(applicationScope) { uri -> localBookImporter.importFromUri(uri) }
    }

    val downloadScheduler: org.hello.reader.feature.library.DownloadScheduler by lazy {
        org.hello.reader.feature.library.WorkManagerDownloadScheduler(appContext)
    }
}
