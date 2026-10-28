package app.screentranslate

import android.app.Activity
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import app.screentranslate.core.Prefs
import app.screentranslate.core.TranslateEngine
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import app.screentranslate.core.Script
import app.screentranslate.core.allLanguages
import app.screentranslate.core.languageName
import app.screentranslate.live.LiveService
import app.screentranslate.live.LiveStartActivity

/** Setup screen: make the app the assistant, pick a language, start live mode. */
class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var assistantStatus: TextView
    private lateinit var liveButton: Button
    private lateinit var packList: LinearLayout
    private val models = RemoteModelManager.getInstance()

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(32))
        }
        val scroll = ScrollView(this).apply { addView(col) }
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION")
            v.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
            insets
        }
        setContentView(scroll)

        col.addView(text("Home Translate", 26f, bold = true))
        col.addView(text("Hold the home button (or the gesture bar) to translate whatever is on screen, on your phone, with no account."))

        col.addView(heading("1. Make it your assistant"))
        assistantStatus = text("")
        col.addView(assistantStatus)
        col.addView(text(
            "Open the settings below, choose Home Translate as the Digital assistant app, and make sure " +
                "\"Use screenshot\" is turned on.\n\nIf holding home opens Circle to Search instead, turn " +
                "Circle to Search off (Settings › Display › Navigation bar, or search Settings for \"Circle to Search\")."
        ))
        col.addView(button("Open assistant settings") { openAssistantSettings() })

        col.addView(heading("2. Translate into"))
        val langs = allLanguages()
        col.addView(spinner(langs.map { languageName(it) }, langs.indexOf(prefs.targetLang)) { prefs.targetLang = langs[it] })

        col.addView(heading("Text to look for"))
        col.addView(text("Pick the writing system you usually translate from. Every option also reads Latin text (English, French, …)."))
        val scripts = Script.values().toList()
        col.addView(spinner(scripts.map { it.label }, scripts.indexOf(prefs.script)) { prefs.script = scripts[it] })

        col.addView(CheckBox(this).apply {
            text = "Only download language packs on Wi-Fi"
            isChecked = prefs.wifiOnly
            setOnCheckedChangeListener { _, c -> prefs.wifiOnly = c }
        })

        col.addView(heading("Language packs"))
        col.addView(text(
            "Each language needs a pack (about 30 MB) to translate offline. Packs download automatically the first " +
                "time you need one, or you can get them here. They stay through app updates."
        ))
        packList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(packList)
        val addable = allLanguages().filter { it != TranslateLanguage.ENGLISH }
        var toAdd = addable.firstOrNull()
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(spinner(addable.map { languageName(it) }, 0) { toAdd = addable[it] },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(Button(this@MainActivity).apply {
                text = "Download"
                isAllCaps = false
                setOnClickListener { toAdd?.let { downloadPack(it, this) } }
            })
        })
        refreshPacks()

        col.addView(heading("3. Live translate (beta)"))
        col.addView(text(
            "Keeps translations on top of any app while you scroll and read. Uses screen capture, so Android shows a " +
                "recording indicator. Tap the floating 文A bubble to pause, long-press it to stop. You can also add the " +
                "\"Live translate\" Quick Settings tile, or tap Live from the hold-home screen."
        ))
        liveButton = button("Start live translate") { toggleLive() }
        col.addView(liveButton)
    }

    override fun onResume() {
        super.onResume()
        val held = Build.VERSION.SDK_INT >= 29 &&
            getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_ASSISTANT)
        assistantStatus.text = if (held) "✓ Home Translate is your assistant. Hold home to try it."
        else "Not set as your assistant yet."
        liveButton.text = if (LiveService.running) "Stop live translate" else "Start live translate"
    }

    private fun refreshPacks() {
        models.getDownloadedModels(TranslateRemoteModel::class.java).addOnSuccessListener { downloaded ->
            if (isFinishing) return@addOnSuccessListener
            packList.removeAllViews()
            val langs = downloaded.map { it.language }.sortedBy { languageName(it) }
            if (langs.isEmpty()) packList.addView(text("No packs downloaded yet."))
            for (lang in langs) {
                packList.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    addView(text("✓ " + languageName(lang)), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    // English is the bridge between every other pair, so keep it.
                    if (lang != TranslateLanguage.ENGLISH) addView(Button(this@MainActivity, null, android.R.attr.borderlessButtonStyle).apply {
                        text = "Delete"
                        isAllCaps = false
                        setOnClickListener { deletePack(lang) }
                    })
                })
            }
        }
    }

    private fun downloadPack(lang: String, button: Button) {
        button.isEnabled = false
        button.text = "Downloading…"
        val conditions = DownloadConditions.Builder().apply { if (prefs.wifiOnly) requireWifi() }.build()
        models.download(TranslateRemoteModel.Builder(lang).build(), conditions)
            .addOnSuccessListener { Toast.makeText(this, "${languageName(lang)} downloaded", Toast.LENGTH_SHORT).show() }
            .addOnFailureListener {
                val why = if (prefs.wifiOnly) " (Wi-Fi only is on)" else ""
                Toast.makeText(this, "Couldn't download ${languageName(lang)}$why", Toast.LENGTH_LONG).show()
            }
            .addOnCompleteListener {
                button.isEnabled = true
                button.text = "Download"
                refreshPacks()
            }
    }

    private fun deletePack(lang: String) {
        models.deleteDownloadedModel(TranslateRemoteModel.Builder(lang).build()).addOnCompleteListener {
            TranslateEngine.forgetModels()
            refreshPacks()
        }
    }

    private fun toggleLive() {
        if (LiveService.running) {
            startService(Intent(this, LiveService::class.java).setAction(LiveService.ACTION_STOP))
            liveButton.text = "Start live translate"
        } else {
            startActivity(Intent(this, LiveStartActivity::class.java))
        }
    }

    private fun openAssistantSettings() {
        val intents = listOf(
            Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (i in intents) {
            try { startActivity(i); return } catch (_: ActivityNotFoundException) {}
        }
    }

    private fun text(s: String, size: Float = 15f, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun heading(s: String) = text(s, 18f, bold = true).apply { setPadding(0, dp(24), 0, dp(4)) }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun spinner(items: List<String>, selected: Int, onPick: (Int) -> Unit) = Spinner(this).apply {
        adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, items)
        if (selected >= 0) setSelection(selected)
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = onPick(pos)
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }
}
