package ai.opencode.term

import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import ai.opencode.term.config.ConfigRepository
import ai.opencode.term.config.ServerConfig
import ai.opencode.term.opencode.OpenCodeClient
import ai.opencode.term.opencode.RemoteSession
import ai.opencode.term.runtime.RuntimeValidator
import ai.opencode.term.security.TokenStorage
import ai.opencode.term.terminal.NativeShellSession
import ai.opencode.term.terminal.TerminalKeyHandler
import ai.opencode.term.terminal.TerminalView
import ai.opencode.term.ui.SettingsDialog
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var configRepo: ConfigRepository
    private lateinit var tokenStorage: TokenStorage
    private lateinit var terminalView: TerminalView
    private lateinit var statusText: TextView
    private lateinit var errorBanner: TextView
    private lateinit var quickBar: LinearLayout

    private var shellSession: NativeShellSession? = null
    private var remoteSession: RemoteSession? = null

    private val toolbarModifiers = object {
        var ctrl = false
        var alt = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        configRepo = ConfigRepository(this)
        tokenStorage = TokenStorage(this)

        terminalView = findViewById(R.id.terminal_view)
        statusText = findViewById(R.id.status_text)
        errorBanner = findViewById(R.id.error_banner)
        quickBar = findViewById(R.id.quick_toolbar)
        findViewById<ImageButton>(R.id.btn_settings).setOnClickListener { openSettings() }

        buildQuickToolbar()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { /* keep session; do not finish */ }
        })

        initialize()
    }

    private fun initialize() {
        val report = RuntimeValidator.validate(this)
        terminalView.fontSizeSp = configRepo.fontSizeSp

        val cfg = configRepo.loadServerConfig()
        if (cfg.isConfigured) {
            startRemoteSession(cfg)
        } else {
            showError(
                report.message.ifBlank { getString(R.string.error_no_server_configured) } +
                    "\nTap ⚙ to configure a server, or use the local shell."
            )
            startShellSession()
        }
    }

    // ---------------- Sessions ----------------

    private fun startShellSession() {
        stopAllSessions()
        val ws = ai.opencode.term.runtime.ProcessEnvironment.workingDir(this)
        val session = NativeShellSession(
            scope = lifecycleScope,
            workingDir = ws.absolutePath,
            maxScrollback = configRepo.scrollbackLines
        )
        session.onExit = { code -> runOnUiThread { setStatus("shell exited ($code)", error = code != 0) } }
        session.start(terminalView.cols(), terminalView.rows())
        terminalView.session = session
        shellSession = session
        setStatus(getString(R.string.status_local_shell), error = false)
    }

    private fun startRemoteSession(cfg: ServerConfig) {
        stopAllSessions()
        val client = OpenCodeClient(cfg, tokenStorage)
        val session = RemoteSession(
            scope = lifecycleScope,
            client = client,
            tokenStorage = tokenStorage,
            config = cfg,
            maxScrollback = configRepo.scrollbackLines
        )
        terminalView.session = session
        remoteSession = session
        session.start(terminalView.cols(), terminalView.rows())

        lifecycleScope.launch {
            setStatus(getString(R.string.status_starting), error = false)
            when (val r = client.checkHealth()) {
                is OpenCodeClient.Result.Ok -> {
                    val host = cfg.baseUrl.removePrefix("https://").removePrefix("http://")
                    setStatus(getString(R.string.status_remote, host), error = false)
                    hideError()
                    client.startEvents(lifecycleScope)
                }
                is OpenCodeClient.Result.Err -> {
                    showError("Server: ${r.message}\nFalling back to local shell.")
                    startShellSession()
                }
            }
        }
    }

    private fun stopAllSessions() {
        shellSession?.stop(); shellSession = null
        remoteSession?.stop(); remoteSession = null
    }

    private fun restartSession() {
        val cfg = configRepo.loadServerConfig()
        if (cfg.isConfigured) startRemoteSession(cfg) else startShellSession()
    }

    // ---------------- Toolbar ----------------

    private fun buildQuickToolbar() {
        val keys = listOf(
            "CTRL" to { toggleModifier { toolbarModifiers.ctrl = !toolbarModifiers.ctrl } },
            "ALT" to { toggleModifier { toolbarModifiers.alt = !toolbarModifiers.alt } },
            "TAB" to { sendKey(TerminalKeyHandler.tab()) },
            "ESC" to { sendKey(TerminalKeyHandler.escape()) },
            "▲" to { sendKey(TerminalKeyHandler.arrowUp()) },
            "▼" to { sendKey(TerminalKeyHandler.arrowDown()) },
            "◀" to { sendKey(TerminalKeyHandler.arrowLeft()) },
            "▶" to { sendKey(TerminalKeyHandler.arrowRight()) },
            "⏎" to { sendKey(TerminalKeyHandler.enter()) },
            "⌫" to { sendKey(TerminalKeyHandler.backspace()) },
            "KB" to { terminalView.hideSoftInput() },
            "CLR" to { terminalView.session?.buffer?.clearAll(); terminalView.invalidate() },
            "↻" to { restartSession() }
        )
        for ((label, action) in keys) {
            val btn = Button(this).apply {
                text = label
                textSize = 12f
                setPadding(20, 4, 20, 4)
                isAllCaps = false
                minimumWidth = 100
                setBackgroundResource(android.R.color.transparent)
                setTextColor(getColor(R.color.terminal_foreground))
                setOnClickListener { action() }
            }
            quickBar.addView(btn, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT, 1f
            ).apply { gravity = Gravity.CENTER })
        }
    }

    private fun toggleModifier(toggle: () -> Unit) {
        toggle()
        val active = toolbarModifiers.ctrl || toolbarModifiers.alt
        quickBar.getChildAt(0).alpha = if (toolbarModifiers.ctrl) 1f else 0.5f
        quickBar.getChildAt(1).alpha = if (toolbarModifiers.alt) 1f else 0.5f
        if (!active) {
            toolbarModifiers.ctrl = false
            toolbarModifiers.alt = false
        }
    }

    private fun sendKey(seq: String) {
        var s = seq
        if (toolbarModifiers.alt) s = TerminalKeyHandler.alt(s)
        if (toolbarModifiers.ctrl && s.length == 1) s = TerminalKeyHandler.ctrl(s[0])
        toolbarModifiers.ctrl = false
        toolbarModifiers.alt = false
        quickBar.getChildAt(0).alpha = 0.5f
        quickBar.getChildAt(1).alpha = 0.5f
        terminalView.sendKey(s)
    }

    // ---------------- Status / settings ----------------

    private fun setStatus(text: String, error: Boolean) {
        statusText.text = text
        statusText.setTextColor(getColor(if (error) R.color.status_error else R.color.status_ok))
    }

    private fun showError(text: String) {
        errorBanner.text = text
        errorBanner.visibility = android.view.View.VISIBLE
    }

    private fun hideError() { errorBanner.visibility = android.view.View.GONE }

    private fun openSettings() {
        SettingsDialog(this, lifecycleScope, configRepo, tokenStorage) {
            hideError()
            restartSession()
        }.show()
    }

    // ---------------- Lifecycle ----------------

    override fun onDestroy() {
        stopAllSessions()
        super.onDestroy()
    }

    override fun onPause() {
        super.onPause()
        // PTY child keeps running while backgrounded; output resumes on return.
    }

    override fun onResume() {
        super.onResume()
        terminalView.invalidate()
    }
}
