package org.hello.reader.app

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import org.hello.reader.core.util.HardwareKeyDispatcher
import org.hello.reader.ui.theme.HelloTheme

class MainActivity : ComponentActivity() {

    private val mainViewModel: MainViewModel by viewModels { MainViewModel.Factory }
    private val pendingOpenBookId = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as HelloApplication).container
        if (savedInstanceState == null) {
            pendingOpenBookId.value = intent?.getStringExtra(EXTRA_OPEN_BOOK_ID)
        }

        setContent {
            val settings by container.readerPreferencesRepository.settings.collectAsStateWithLifecycle(initialValue = org.hello.reader.core.model.ReaderSettings())
            val openBookId by pendingOpenBookId.collectAsStateWithLifecycle()
            HelloTheme(readerSettings = settings, useReaderTheme = settings.appUsesReaderTheme) {
                Surface(Modifier.fillMaxSize()) {
                    val startState by mainViewModel.startState.collectAsStateWithLifecycle()
                    when (startState) {
                        MainViewModel.StartState.Loading -> Box(Modifier.fillMaxSize()) {
                            CircularProgressIndicator(Modifier.align(Alignment.Center))
                        }
                        MainViewModel.StartState.Ready -> HelloNavHost(
                            vault = container.vault,
                            authExpired = container.authExpired,
                            initialBookId = openBookId,
                            onBookOpened = {
                                pendingOpenBookId.value = null
                                intent?.removeExtra(EXTRA_OPEN_BOOK_ID)
                            },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(EXTRA_OPEN_BOOK_ID)?.let { bookId ->
            pendingOpenBookId.value = bookId
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (HardwareKeyDispatcher.onKeyDown(keyCode)) {
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    companion object {
        const val EXTRA_OPEN_BOOK_ID = "org.hello.reader.extra.OPEN_BOOK_ID"
    }
}
