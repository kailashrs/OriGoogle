package io.github.kailashrs.origoogle.ui

import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import com.google.android.material.R as MaterialR
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.divider.MaterialDivider
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.materialswitch.MaterialSwitch
import io.github.kailashrs.origoogle.R
import io.github.kailashrs.origoogle.core.Features
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private var preferences: SharedPreferences? = null
    private val switches = mutableMapOf<String, MaterialSwitch>()
    private val pending = mutableSetOf<Scope>()
    private lateinit var applyButton: ExtendedFloatingActionButton

    /** What has to restart for a change to apply. */
    private enum class Scope { SYSTEM, LAUNCHER }

    private class Entry(
        val title: String,
        val summary: String,
        val icon: Int,
        val scope: Scope,
        val experimental: Boolean = false,
    )

    private class Section(val title: String, val entries: Map<String, Entry>)

    private val sections = listOf(
        Section("Google services", linkedMapOf(
            Features.GMS to Entry("Global Google services",
                "Removes the China restrictions so Play services enables Timeline, Quick Share, Find Hub and other global features.",
                R.drawable.ic_feature_gms, Scope.SYSTEM),
            Features.QUICK_SHARE to Entry("Quick Share in share sheet",
                "Pins Quick Share as the first app in the share panel.",
                R.drawable.ic_feature_gms, Scope.SYSTEM),
        )),
        Section("Gestures", linkedMapOf(
            Features.CIRCLE_TO_SEARCH to Entry("Circle to Search",
                "Long-press the gesture handle to search anything on screen.",
                R.drawable.ic_feature_search, Scope.SYSTEM),
            Features.CORNER_ASSISTANT to Entry("Corner swipe for assistant",
                "Adds Google Assistant to the bottom-corner options in the Interaction Cube (System navigation). Your current corner actions stay as they are until you pick it.",
                R.drawable.ic_feature_corner, Scope.SYSTEM),
            Features.POWER_ASSISTANT to Entry("Hold power for assistant",
                "Holding the power button opens Gemini instead of Jovi.",
                R.drawable.ic_feature_power, Scope.SYSTEM),
        )),
        Section("Home screen", linkedMapOf(
            Features.DISCOVER_FEED to Entry("Google Discover",
                "Shows Discover on the leftmost home screen instead of vivo's left screen.",
                R.drawable.ic_feature_feed, Scope.LAUNCHER, experimental = true),
        )),
    )

    private val scopeOf: Map<String, Scope> = sections.flatMap { it.entries.entries }.associate { it.key to it.value.scope }

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById(R.id.toolbar))

        val container = findViewById<LinearLayout>(R.id.sections)
        for (section in sections) container.addView(buildSection(container, section))

        applyButton = findViewById(R.id.apply)
        applyButton.setOnClickListener { apply() }
        val margin = resources.getDimensionPixelSize(R.dimen.fab_margin)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.scroll)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.updatePadding(left = bars.left, right = bars.right, bottom = bars.bottom)
            applyButton.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = bars.bottom + margin
                rightMargin = bars.right + margin
            }
            insets
        }

        showStatus(active = null, detail = null)
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(service: XposedService) {
                val prefs = runCatching { service.getRemotePreferences(Features.GROUP) }.getOrNull()
                runOnUiThread { bind(prefs, "${service.frameworkName} ${service.frameworkVersion} · API ${service.apiVersion}") }
            }

            override fun onServiceDied(service: XposedService) {
                runOnUiThread { bind(null, null) }
            }
        })
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    private fun buildSection(parent: LinearLayout, section: Section): View {
        val view = layoutInflater.inflate(R.layout.item_section, parent, false)
        view.findViewById<TextView>(R.id.section_title).text = section.title
        val rows = view.findViewById<LinearLayout>(R.id.section_rows)
        section.entries.entries.forEachIndexed { index, (key, entry) ->
            if (index > 0) rows.addView(MaterialDivider(this).apply {
                dividerInsetStart = resources.getDimensionPixelSize(R.dimen.divider_inset)
                dividerInsetEnd = resources.getDimensionPixelSize(R.dimen.fab_margin)
            })
            rows.addView(buildRow(rows, key, entry))
        }
        return view
    }

    private fun buildRow(parent: LinearLayout, key: String, entry: Entry): View {
        val row = layoutInflater.inflate(R.layout.item_feature, parent, false)
        row.findViewById<ImageView>(R.id.feature_icon).setImageResource(entry.icon)
        row.findViewById<TextView>(R.id.feature_title).text = entry.title
        row.findViewById<TextView>(R.id.feature_summary).text = entry.summary
        row.findViewById<Chip>(R.id.feature_experimental).visibility = if (entry.experimental) View.VISIBLE else View.GONE
        val toggle = row.findViewById<MaterialSwitch>(R.id.feature_switch)
        toggle.setOnCheckedChangeListener { button, checked -> if (button.isPressed) save(key, checked) }
        row.setOnClickListener {
            if (toggle.isEnabled) {
                toggle.toggle()
                save(key, toggle.isChecked)
            }
        }
        switches[key] = toggle
        return row
    }

    private fun showStatus(active: Boolean?, detail: String?) {
        val card = findViewById<MaterialCardView>(R.id.status_card)
        val (containerAttr, onContainerAttr) = when (active) {
            true -> MaterialR.attr.colorPrimaryContainer to MaterialR.attr.colorOnPrimaryContainer
            false -> MaterialR.attr.colorErrorContainer to MaterialR.attr.colorOnErrorContainer
            null -> MaterialR.attr.colorSurfaceContainerHighest to MaterialR.attr.colorOnSurfaceVariant
        }
        val onContainer = MaterialColors.getColor(card, onContainerAttr)
        card.setCardBackgroundColor(MaterialColors.getColor(card, containerAttr))
        findViewById<ImageView>(R.id.status_icon).apply {
            setImageResource(if (active == false) R.drawable.ic_status_off else R.drawable.ic_status_ok)
            imageTintList = ColorStateList.valueOf(onContainer)
        }
        findViewById<TextView>(R.id.status_title).apply {
            setTextColor(onContainer)
            setText(when (active) {
                true -> R.string.status_active
                false -> R.string.status_inactive
                null -> R.string.status_connecting
            })
        }
        findViewById<TextView>(R.id.status_detail).apply {
            setTextColor(onContainer)
            text = when (active) {
                true -> detail
                false -> getString(R.string.status_inactive_detail)
                null -> getString(R.string.status_connecting_detail)
            }
        }
    }

    private fun bind(prefs: SharedPreferences?, framework: String?) {
        preferences = prefs
        showStatus(active = prefs != null, detail = framework)
        for ((key, toggle) in switches) {
            toggle.isEnabled = prefs != null
            toggle.isChecked = prefs?.getBoolean(key, Features.ALL[key] ?: false) ?: (Features.ALL[key] ?: false)
        }
    }

    private fun save(key: String, enabled: Boolean) {
        val prefs = preferences ?: return
        scopeOf[key]?.let { pending += it }
        updateApplyButton()
        worker.execute {
            val ok = prefs.edit().putBoolean(key, enabled).commit()
            if (ok && enabled && key == Features.DISCOVER_FEED) ControlActions.prepareFeed(this)
            if (!ok) runOnUiThread {
                showStatus(active = false, detail = null)
                findViewById<TextView>(R.id.status_title).setText(R.string.status_save_failed)
            }
        }
    }

    private fun updateApplyButton() {
        if (pending.isEmpty()) {
            applyButton.hide()
            return
        }
        applyButton.setText(if (Scope.SYSTEM in pending) R.string.apply_soft_reboot else R.string.apply_launcher)
        applyButton.show()
    }

    private fun apply() {
        if (Scope.SYSTEM !in pending) {
            worker.execute { ControlActions.restartUi(this) }
            pending.clear()
            updateApplyButton()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.soft_reboot_title)
            .setMessage(R.string.soft_reboot_message)
            .setPositiveButton(R.string.soft_reboot_confirm) { _, _ -> worker.execute { ControlActions.softReboot(this) } }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
