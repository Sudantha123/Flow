package io.github.aedev.flow.ui.screens.settings

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import io.github.aedev.flow.innertube.YouTube
import io.github.aedev.flow.innertube.utils.parseCookieString
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
fun YouTubeAccountScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Sign in with your Google account to sync YouTube Music library.") }
    var webView by remember { mutableStateOf<WebView?>(null) }

    suspend fun finishLogin() {
        val view = webView ?: return
        loading = true
        repeat(20) {
            val cookie = CookieManager.getInstance().getCookie("https://music.youtube.com").orEmpty()
            val cookies = runCatching { parseCookieString(cookie) }.getOrDefault(emptyMap())
            val visitor = CompletableDeferred<String?>()
            val sync = CompletableDeferred<String?>()

            val js = object {
                @JavascriptInterface
                fun visitorData(value: String?) { if (!visitor.isCompleted) visitor.complete(value) }
                @JavascriptInterface
                fun dataSyncId(value: String?) { if (!sync.isCompleted) sync.complete(value) }
            }
            view.addJavascriptInterface(js, "FlowAuth")

            view.loadUrl("javascript:FlowAuth.visitorData(window.yt&&window.yt.config_?window.yt.config_.VISITOR_DATA:null)")
            view.loadUrl("javascript:FlowAuth.dataSyncId(window.yt&&window.yt.config_?window.yt.config_.DATASYNC_ID:null)")

            val visitorData = withTimeoutOrNull(1000) { visitor.await() }
            val dataSyncId = withTimeoutOrNull(1000) { sync.await() }?.orEmpty()?.substringBefore("||").orEmpty()

            if ("SAPISID" in cookies && !visitorData.isNullOrBlank()) {
                YouTube.cookie = cookie
                YouTube.visitorData = visitorData
                YouTube.dataSyncId = dataSyncId
                YouTube.useLoginForBrowse = true

                val account = YouTube.accountInfo().getOrNull()
                if (account != null) {
                    YouTubeAccountAuth.save(
                        context = context,
                        cookie = cookie,
                        visitorData = visitorData,
                        dataSyncId = dataSyncId,
                        name = account.name,
                        email = account.email.orEmpty(),
                        handle = account.channelHandle.orEmpty(),
                    )
                    status = "Signed in as ${account.name}"
                    loading = false
                    navController.popBackStack()
                    return
                }
            }
            delay(500)
        }
        loading = false
        status = "Login could not be verified. Please finish the Google/YouTube Music sign-in and try again."
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("YouTube account", style = MaterialTheme.typography.headlineSmall)
        Text(status, style = MaterialTheme.typography.bodyMedium)

        if (loading) {
            CircularProgressIndicator()
        }

        Box(modifier = Modifier.weight(1f).fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String?) {
                                if (url?.startsWith("https://music.youtube.com") == true) {
                                    scope.launch { finishLogin() }
                                }
                            }
                        }
                        webView = this
                        loadUrl("https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com")
                    }
                },
            )
        }

        Button(onClick = { scope.launch { finishLogin() } }, enabled = !loading) {
            Text("Check account")
        }
        Button(onClick = { navController.popBackStack() }) {
            Text("Back")
        }
    }
}

object YouTubeAccountAuth {
    private const val PREFS = "youtube_account_session"
    private const val COOKIE = "cookie"
    private const val VISITOR = "visitor_data"
    private const val SYNC = "data_sync_id"
    private const val NAME = "name"
    private const val EMAIL = "email"
    private const val HANDLE = "handle"

    fun restore(context: Context): Boolean {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cookie = p.getString(COOKIE, null)
        val visitor = p.getString(VISITOR, null)
        if (cookie.isNullOrBlank() || visitor.isNullOrBlank()) return false
        YouTube.cookie = cookie
        YouTube.visitorData = visitor
        YouTube.dataSyncId = p.getString(SYNC, "").orEmpty()
        YouTube.useLoginForBrowse = true
        return true
    }

    fun save(
        context: Context,
        cookie: String,
        visitorData: String,
        dataSyncId: String,
        name: String,
        email: String,
        handle: String,
    ) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(COOKIE, cookie)
            .putString(VISITOR, visitorData)
            .putString(SYNC, dataSyncId)
            .putString(NAME, name)
            .putString(EMAIL, email)
            .putString(HANDLE, handle)
            .apply()
    }

    fun signOut(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        YouTube.cookie = null
        YouTube.visitorData = null
        YouTube.dataSyncId = null
        YouTube.useLoginForBrowse = false
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
    }

    fun accountName(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(NAME, null)
}
