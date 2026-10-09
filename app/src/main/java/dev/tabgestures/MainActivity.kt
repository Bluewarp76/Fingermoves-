package dev.tabgestures

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import rikka.shizuku.Shizuku

class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var shizukuText: TextView
    private lateinit var serviceText: TextView
    private lateinit var touchText: TextView
    private lateinit var lastText: TextView
    private lateinit var permissionButton: Button
    private lateinit var toggleButton: Button

    private val stateListener = { refresh() }
    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, _ -> runOnUiThread { refresh() } }
    private val binderListener = Shizuku.OnBinderReceivedListener { runOnUiThread { refresh() } }
    private val deadListener = Shizuku.OnBinderDeadListener { runOnUiThread { refresh() } }

    private val dp get() = resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Config.prefs(this)
        setContentView(buildUi())
        Shizuku.addRequestPermissionResultListener(permissionListener)
        Shizuku.addBinderReceivedListenerSticky(binderListener)
        Shizuku.addBinderDeadListener(deadListener)
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        Shizuku.removeBinderReceivedListener(binderListener)
        Shizuku.removeBinderDeadListener(deadListener)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        AppState.addListener(stateListener)
        refresh()
    }

    override fun onPause() {
        AppState.removeListener(stateListener)
        super.onPause()
    }

    private fun shizukuState(): String = when {
        !Shizuku.pingBinder() -> "Not running – open Shizuku and start it (Wireless debugging)"
        Shizuku.isPreV11() || Shizuku.getVersion() < 11 -> "Too old – update Shizuku"
        Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> "Running – permission not granted yet"
        else -> "Running – permission granted"
    }

    private fun refresh() {
        val running = Shizuku.pingBinder()
        val granted = running && !Shizuku.isPreV11() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        shizukuText.text = "Shizuku: ${shizukuState()}"
        serviceText.text = "Service: ${if (AppState.serviceRunning) AppState.connection else "Stopped"}"
        touchText.text = "Touchscreen: ${AppState.touchscreen}"
        lastText.text = AppState.lastGesture
        permissionButton.visibility = if (running && !granted) View.VISIBLE else View.GONE
        toggleButton.text = if (AppState.serviceRunning) "Stop gestures" else "Start gestures"
    }

    // ---- UI ----

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * dp).toInt()
            setPadding(pad, pad, pad, pad)
        }

        root.addView(text("Tab Gestures", 26f, bold = true))
        root.addView(text("iPad-style multi-finger gestures, powered by Shizuku.", 14f).also { it.alpha = 0.7f })

        root.addView(header("Status"))
        shizukuText = text("", 15f).also(root::addView)
        serviceText = text("", 15f).also(root::addView)
        touchText = text("", 15f).also(root::addView)

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        permissionButton = Button(this).apply {
            text = "Grant Shizuku permission"
            setOnClickListener { Shizuku.requestPermission(1) }
        }
        toggleButton = Button(this).apply {
            setOnClickListener {
                if (AppState.serviceRunning) GestureService.stop(this@MainActivity)
                else GestureService.start(this@MainActivity)
            }
        }
        val battery = Button(this).apply {
            text = "Battery: unrestricted"
            setOnClickListener {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")),
                )
            }
        }
        buttons.addView(toggleButton)
        buttons.addView(permissionButton)
        buttons.addView(battery)
        root.addView(buttons)

        root.addView(header("Last gesture (use this to test)"))
        lastText = text("–", 18f, bold = true).also(root::addView)

        for (fingers in Config.FINGERS) {
            root.addView(header("$fingers fingers"))
            for (g in GestureName.entries) root.addView(mappingRow(fingers, g))
        }

        root.addView(header("Tuning"))
        root.addView(slider("Swipe distance", Config.KEY_SWIPE, 10, 5, 30) { "$it% of screen" })
        root.addView(slider("Pinch amount", Config.KEY_PINCH, 35, 15, 60) { "$it% closer / wider" })
        root.addView(
            choiceRow(
                "Direction correction",
                listOf("None", "Rotate 90°", "Rotate 180°", "Rotate 270°"),
                prefs.getInt(Config.KEY_ROTATION_FIX, 0),
            ) { prefs.edit().putInt(Config.KEY_ROTATION_FIX, it).apply() },
        )

        root.addView(header("Tips"))
        root.addView(
            text(
                "• Previous/Next app replay the system's swipe along the navigation handle, so " +
                    "they need Settings › Display › Navigation bar › Swipe gestures.\n" +
                    "• If swipes come out in the wrong direction, change Direction correction.\n" +
                    "• Touches still reach the app underneath, just like on iPad.\n" +
                    "• Shizuku has to be restarted after every reboot; gestures reconnect automatically.\n" +
                    "• Set this app's battery usage to Unrestricted so One UI doesn't kill it.",
                14f,
            ),
        )

        return ScrollView(this).apply { addView(root) }
    }

    private fun text(s: String, size: Float, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, (4 * dp).toInt(), 0, (4 * dp).toInt())
    }

    private fun header(s: String) = text(s, 18f, bold = true).apply {
        setPadding(0, (24 * dp).toInt(), 0, (8 * dp).toInt())
    }

    private fun mappingRow(fingers: Int, gesture: GestureName): View {
        val key = Config.key(fingers, gesture)
        val actions = Action.entries
        return choiceRow(gesture.label, actions.map { it.label }, actions.indexOf(Config.action(prefs, key))) {
            Config.setAction(prefs, key, actions[it])
        }
    }

    private fun choiceRow(label: String, options: List<String>, selected: Int, onPick: (Int) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(text(label, 16f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, options)
            setSelection(selected)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) =
                    onPick(position)
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        row.addView(spinner, LinearLayout.LayoutParams((240 * dp).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT))
        return row
    }

    private fun slider(label: String, key: String, default: Int, min: Int, max: Int, describe: (Int) -> String): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val title = text("", 16f)
        val current = prefs.getInt(key, default)
        title.text = "$label: ${describe(current)}"
        val bar = SeekBar(this).apply {
            this.min = min
            this.max = max
            progress = current
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                    title.text = "$label: ${describe(value)}"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) = Unit
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    prefs.edit().putInt(key, progress).apply()
                }
            })
        }
        col.addView(title)
        col.addView(bar)
        return col
    }
}
