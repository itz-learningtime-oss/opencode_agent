package ai.opencode.term.ui

import android.app.Dialog
import android.content.Context
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import ai.opencode.term.R
import ai.opencode.term.config.ConfigRepository
import ai.opencode.term.config.ServerConfig
import ai.opencode.term.opencode.OpenCodeClient
import ai.opencode.term.security.TokenStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Server configuration dialog. Password field is masked; saved token shows as
 * "•••• saved" until replaced. Test Connection performs a real /global/health call.
 */
class SettingsDialog(
    private val context: Context,
    private val scope: CoroutineScope,
    private val configRepo: ConfigRepository,
    private val tokenStorage: TokenStorage,
    private val onSaved: () -> Unit
) {

    private val dialog = Dialog(context)
    private lateinit var urlEdit: EditText
    private lateinit var userEdit: EditText
    private lateinit var tokenEdit: EditText
    private lateinit var httpCheck: CheckBox
    private lateinit var statusText: TextView
    private lateinit var testBtn: Button
    private lateinit var saveBtn: Button
    private lateinit var clearBtn: Button
    private lateinit var cancelBtn: Button

    init {
        val view = buildView()
        dialog.setContentView(view)
        dialog.setTitle(context.getString(ai.opencode.term.R.string.settings_title))
        loadCurrent()
    }

    private fun buildView(): View {
        val ctx = context
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }

        fun label(text: String) = TextView(ctx).apply { this.text = text }

        urlEdit = EditText(ctx).apply { hint = ctx.getString(ai.opencode.term.R.string.server_url_hint); inputType = InputType.TYPE_TEXT_URI }
        userEdit = EditText(ctx).apply { inputType = InputType.TYPE_TEXT_VARIATION_USERNAME }
        tokenEdit = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        httpCheck = CheckBox(ctx).apply { text = ctx.getString(ai.opencode.term.R.string.allow_http) }
        statusText = TextView(ctx).apply { setPadding(0, 16, 0, 8) }

        testBtn = Button(ctx).apply { text = ctx.getString(ai.opencode.term.R.string.test_connection); setOnClickListener { onTest() } }
        saveBtn = Button(ctx).apply { text = ctx.getString(ai.opencode.term.R.string.save); setOnClickListener { onSave() } }
        clearBtn = Button(ctx).apply { text = ctx.getString(ai.opencode.term.R.string.clear_credentials); setOnClickListener { onClear() } }
        cancelBtn = Button(ctx).apply { text = ctx.getString(ai.opencode.term.R.string.cancel); setOnClickListener { dialog.dismiss() } }

        layout.addView(label(ctx.getString(ai.opencode.term.R.string.server_url_label)))
        layout.addView(urlEdit)
        layout.addView(label(ctx.getString(ai.opencode.term.R.string.username_label)))
        layout.addView(userEdit)
        layout.addView(label(ctx.getString(ai.opencode.term.R.string.token_label)))
        layout.addView(tokenEdit)
        layout.addView(httpCheck)
        layout.addView(TextView(ctx).apply {
            text = ctx.getString(ai.opencode.term.R.string.http_warning)
            textSize = 12f
        })
        layout.addView(statusText)
        layout.addView(testBtn)
        layout.addView(saveBtn)
        layout.addView(clearBtn)
        layout.addView(cancelBtn)
        return layout
    }

    private fun loadCurrent() {
        val cfg = configRepo.loadServerConfig()
        urlEdit.setText(cfg.baseUrl)
        userEdit.setText(cfg.username)
        httpCheck.isChecked = cfg.allowHttp
        if (tokenStorage.hasToken()) {
            tokenEdit.hint = "•••• (saved — type to replace)"
        }
    }

    private fun currentConfigDraft(): ServerConfig =
        configRepo.loadServerConfig().copy(
            baseUrl = urlEdit.text.toString().trim(),
            username = userEdit.text.toString().trim(),
            allowHttp = httpCheck.isChecked
        )

    private fun setStatus(msg: String, error: Boolean) {
        statusText.text = msg
        statusText.setTextColor(
            context.getColor(if (error) ai.opencode.term.R.color.status_error else ai.opencode.term.R.color.status_ok)
        )
    }

    private fun onTest() {
        val draft = currentConfigDraft()
        val err = ServerConfig.validate(draft.baseUrl, draft.allowHttp)
        if (err != null) { setStatus(err, true); return }
        setStatus("Testing…", false)
        testBtn.isEnabled = false
        val client = OpenCodeClient(draft, tokenStorage)
        scope.launch {
            when (val r = client.checkHealth()) {
                is OpenCodeClient.Result.Ok -> setStatus("OK — server v${r.value.version}", false)
                is OpenCodeClient.Result.Err -> setStatus("${r.kind}: ${r.message}", true)
            }
            testBtn.isEnabled = true
        }
    }

    private fun onSave() {
        val draft = currentConfigDraft()
        val err = ServerConfig.validate(draft.baseUrl, draft.allowHttp)
        if (err != null) { setStatus(err, true); return }
        val token = tokenEdit.text.toString()
        configRepo.saveServerConfig(draft)
        if (token.isNotEmpty()) tokenStorage.saveToken(token)
        dialog.dismiss()
        onSaved()
    }

    private fun onClear() {
        tokenStorage.clearToken()
        tokenEdit.text.clear()
        tokenEdit.hint = ""
        setStatus("Saved credentials cleared", false)
    }

    fun show() = dialog.show()
}
