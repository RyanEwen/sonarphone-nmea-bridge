package ca.dynamicsolutions.sonarbridge

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.R as MR

/**
 * The Settings tab: language, network, passphrase, units, sonar display, device
 * options, alarm, demo mode, and the About row. Edits are drafts until [save]
 * writes them through [AppSettings] (and the passphrase through [SonarPassphrase]).
 */
class SettingsTab(private val activity: AppCompatActivity, private val settings: AppSettings) {
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()

    /** True while a language change recreates the screen, so its drafts are not committed. */
    var changingLanguage = false
        private set

    private lateinit var languageButton: MaterialButton
    private lateinit var modeGroup: RadioGroup
    private lateinit var ssidLayout: TextInputLayout
    private lateinit var ssidEdit: TextInputEditText
    internal lateinit var passEdit: TextInputEditText
    private lateinit var demoSwitch: MaterialSwitch
    private lateinit var distGroup: MaterialButtonToggleGroup
    private lateinit var tempGroup: MaterialButtonToggleGroup
    private lateinit var textGroup: MaterialButtonToggleGroup
    private lateinit var beamGroup: MaterialButtonToggleGroup
    private lateinit var alarmField: UnitField
    private lateinit var rangeField: UnitField
    private lateinit var gainSlider: Slider
    private lateinit var clarityGroup: MaterialButtonToggleGroup
    private lateinit var noiseGroup: MaterialButtonToggleGroup
    private lateinit var styleGroup: MaterialButtonToggleGroup
    private lateinit var fishGroup: MaterialButtonToggleGroup
    private lateinit var keelField: UnitField
    private lateinit var tempOffField: UnitField

    internal val keelEdit get() = keelField.edit
    internal val alarmEdit get() = alarmField.edit

    /** Fields shown in feet or metres, re-rendered when the distance unit changes. */
    private val lengthFields get() = listOf(rangeField, keelField, alarmField)

    /** The tab's root view, built once per activity. */
    val view: View = build()

    private fun build(): View {
        val saved = settings.read()
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(16))
        }
        fun header(text: String, topPad: Int = 16) = ListRow.heading(activity, text, topPad)
        fun note(text: String) = TextView(activity).apply {
            this.text = text
            setTextAppearance(MR.style.TextAppearance_Material3_BodySmall)
            alpha = 0.7f
            setPadding(0, dp(4), 0, dp(8))
        }

        root.addView(header(activity.getString(R.string.language_heading), 0))
        languageButton = MaterialButton(activity, null, MR.attr.materialButtonOutlinedStyle).apply {
            text = AppLanguage.choices[AppLanguage.selectedTag(activity)]
                ?: activity.getString(R.string.follow_device)
            setOnClickListener { showLanguagePicker() }
        }
        root.addView(languageButton)
        root.addView(note(activity.getString(R.string.language_note)))
        root.addView(header(activity.getString(R.string.network_heading)))
        modeGroup = RadioGroup(activity)
        listOf(
            activity.getString(R.string.network_sonarphone),
            activity.getString(R.string.network_tbox),
            activity.getString(R.string.network_specific),
        ).forEachIndexed { i, label ->
            modeGroup.addView(
                RadioButton(activity).apply {
                    id = i + 1
                    text = label
                },
            )
        }
        root.addView(modeGroup)

        ssidEdit = TextInputEditText(activity).apply {
            setText(saved.ssid)
        }
        ssidLayout = TextInputLayout(activity).apply {
            hint = activity.getString(R.string.ssid_label)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) }
            addView(ssidEdit)
        }
        root.addView(ssidLayout)
        root.addView(
            note(
                activity.getString(R.string.network_note),
            ),
        )

        root.addView(header(activity.getString(R.string.password_heading)))
        passEdit = TextInputEditText(activity).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(SonarPassphrase.get(activity))
        }
        root.addView(
            TextInputLayout(activity).apply {
                hint = activity.getString(R.string.password_label)
                endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
                addView(passEdit)
            },
        )
        root.addView(note(activity.getString(R.string.factory_password)))

        root.addView(header(activity.getString(R.string.units_heading)))
        fun segBtn(id: Int, label: String) = MaterialButton(activity, null, MR.attr.materialButtonOutlinedStyle).apply {
            this.id = id
            text = label
        }

        // add a toggle group full-width, its buttons sharing the width equally
        fun addGroup(g: MaterialButtonToggleGroup, topPad: Int = 8) {
            g.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(topPad) }
            for (i in 0 until g.childCount) {
                g.getChildAt(i).layoutParams =
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            root.addView(g)
        }
        distGroup = MaterialButtonToggleGroup(activity).apply {
            isSingleSelection = true
            addView(segBtn(1, activity.getString(R.string.feet)))
            addView(segBtn(2, activity.getString(R.string.meters)))
            check(if (saved.feet) 1 else 2)
        }
        addGroup(distGroup)
        tempGroup = MaterialButtonToggleGroup(activity).apply {
            isSingleSelection = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) }
            addView(segBtn(1, activity.getString(R.string.celsius)))
            addView(segBtn(2, activity.getString(R.string.fahrenheit)))
            check(if (saved.fahrenheit) 2 else 1)
        }
        addGroup(tempGroup)
        root.addView(note(activity.getString(R.string.units_note)))

        root.addView(header(activity.getString(R.string.text_size_heading)))
        textGroup = MaterialButtonToggleGroup(activity).apply {
            isSingleSelection = true
            addView(segBtn(1, activity.getString(R.string.text_normal)))
            addView(segBtn(2, activity.getString(R.string.text_large)))
            addView(segBtn(3, activity.getString(R.string.text_xl)))
            check(
                when (saved.textSize) {
                    "large" -> 2
                    "xl" -> 3
                    else -> 1
                },
            )
        }
        addGroup(textGroup)

        root.addView(header(activity.getString(R.string.sonar_heading)))
        gainSlider = Slider(activity).apply {
            valueFrom = 50f
            valueTo = 200f
            stepSize = 10f
            value = saved.gainPercent.toFloat()
            setLabelFormatter { v ->
                activity.getString(R.string.gain_percent, java.text.NumberFormat.getIntegerInstance(AppLanguage.regionalLocale(activity)).format(v.toInt()))
            }
        }
        root.addView(TextView(activity).apply { text = activity.getString(R.string.gain_label) })
        root.addView(gainSlider)
        root.addView(TextView(activity).apply { text = activity.getString(R.string.display_style) })
        styleGroup = MaterialButtonToggleGroup(activity).apply {
            isSingleSelection = true
            addView(segBtn(1, activity.getString(R.string.style_modern)))
            addView(segBtn(2, activity.getString(R.string.style_classic)))
            check(saved.displayStyle + 1)
        }
        addGroup(styleGroup)
        root.addView(TextView(activity).apply { text = activity.getString(R.string.fish_markers) })
        fishGroup = MaterialButtonToggleGroup(activity).apply {
            isSingleSelection = true
            addView(segBtn(1, activity.getString(R.string.off)))
            addView(segBtn(2, activity.getString(R.string.fish)))
            addView(segBtn(3, activity.getString(R.string.fish_depth)))
            check(saved.fishMarkers + 1)
        }
        addGroup(fishGroup)
        root.addView(
            note(
                activity.getString(R.string.style_note),
            ),
        )

        root.addView(TextView(activity).apply { text = activity.getString(R.string.surface_clarity) })
        clarityGroup = MaterialButtonToggleGroup(activity).apply {
            isSingleSelection = true
            addView(segBtn(1, activity.getString(R.string.off)))
            addView(segBtn(2, activity.getString(R.string.low)))
            addView(segBtn(3, activity.getString(R.string.medium)))
            addView(segBtn(4, activity.getString(R.string.high)))
            check(saved.surfaceClarity + 1)
        }
        addGroup(clarityGroup)
        root.addView(TextView(activity).apply { text = activity.getString(R.string.noise_filter) })
        noiseGroup = MaterialButtonToggleGroup(activity).apply {
            isSingleSelection = true
            addView(segBtn(1, activity.getString(R.string.off)))
            addView(segBtn(2, activity.getString(R.string.low)))
            addView(segBtn(3, activity.getString(R.string.medium)))
            addView(segBtn(4, activity.getString(R.string.high)))
            check(saved.noiseFilter + 1)
        }
        addGroup(noiseGroup)
        root.addView(
            note(
                activity.getString(R.string.filters_note),
            ),
        )

        root.addView(note(activity.getString(R.string.numeric_input_note)))
        root.addView(header(activity.getString(R.string.device_heading)))
        beamGroup = MaterialButtonToggleGroup(activity).apply {
            isSingleSelection = true
            addView(segBtn(1, activity.getString(R.string.beam_narrow)))
            addView(segBtn(2, activity.getString(R.string.beam_wide)))
            addView(segBtn(3, activity.getString(R.string.beam_single)))
            check(
                when (saved.beam) {
                    "40" -> 2
                    "125" -> 3
                    else -> 1
                },
            )
        }
        addGroup(beamGroup)
        root.addView(
            note(
                activity.getString(R.string.beam_note),
            ),
        )
        rangeField = unitField(
            UnitScale.LENGTH,
            InputType.TYPE_CLASS_NUMBER,
            format = "%.0f",
            hint = { activity.getString(R.string.range_hint, it) },
            shows = { it > 0 },
            topMargin = dp(8),
        )
        root.addView(rangeField.layout)
        root.addView(
            note(
                activity.getString(R.string.range_note),
            ),
        )

        keelField = unitField(
            UnitScale.LENGTH,
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED,
            format = "%.1f",
            hint = { activity.getString(R.string.keel_hint, it) },
            shows = { it != 0.0 },
            topMargin = dp(8),
        )
        root.addView(keelField.layout)
        tempOffField = unitField(
            UnitScale.TEMPERATURE_CHANGE,
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED,
            format = "%.1f",
            hint = { activity.getString(R.string.temperature_hint, it) },
            shows = { it != 0.0 },
        )
        root.addView(tempOffField.layout)
        root.addView(
            note(
                activity.getString(R.string.offset_note),
            ),
        )

        root.addView(header(activity.getString(R.string.alarm_heading)))
        alarmField = unitField(
            UnitScale.LENGTH,
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL,
            format = "%.1f",
            hint = { activity.getString(R.string.alarm_hint, it) },
            shows = { it > 0 },
        )
        root.addView(alarmField.layout)

        // Show each unit-bearing value in the saved units, and keep the displayed unit
        // and the unit save() parses in step whenever a Units toggle changes.
        rangeField.show(saved.deviceRangeM.toDouble(), saved.feet)
        keelField.show(saved.keelOffsetM.toDouble(), saved.feet)
        alarmField.show(saved.alarmM.toDouble(), saved.feet)
        tempOffField.show(saved.tempOffsetC.toDouble(), saved.fahrenheit)
        distGroup.addOnButtonCheckedListener { _, id, checked ->
            if (checked) lengthFields.forEach { it.switchUnit(inAlternate = id == 1) }
        }
        tempGroup.addOnButtonCheckedListener { _, id, checked ->
            if (checked) tempOffField.switchUnit(inAlternate = id == 2)
        }
        root.addView(note(activity.getString(R.string.alarm_note)))

        root.addView(header(activity.getString(R.string.demo_heading)))
        demoSwitch = MaterialSwitch(activity).apply {
            text = activity.getString(R.string.demo_switch)
            isChecked = saved.demo
        }
        root.addView(demoSwitch)
        root.addView(
            note(
                activity.getString(R.string.demo_note),
            ),
        )
        root.addView(
            note(
                activity.getString(R.string.settings_note),
            ),
        )

        // Last row of Settings (BRAND-8): name, version, updates and links live on the About screen.
        root.addView(ListRow.heading(activity, activity.getString(R.string.about_heading)))
        root.addView(
            ListRow.create(activity, activity.getString(R.string.about_version, BuildConfig.VERSION_NAME)) {
                activity.startActivity(Intent(activity, AboutActivity::class.java))
            },
        )

        val mode = saved.networkMode
        modeGroup.check(mode + 1)
        ssidLayout.isEnabled = AppSettings.NETWORK_PATTERNS[mode] == null
        modeGroup.setOnCheckedChangeListener { _, checkedId ->
            ssidLayout.isEnabled = AppSettings.NETWORK_PATTERNS[checkedId - 1] == null
        }
        return ScrollView(activity).apply { addView(root) }
    }

    /** Commit every draft: settings through [AppSettings], the passphrase through [SonarPassphrase]. */
    fun save() {
        // Each field reads its text in the unit it displays, never the previously saved unit.
        val alarmM = alarmField.baseValue()
        val rangeM = rangeField.baseValue().coerceIn(0.0, 73.0)
        SonarPassphrase.set(activity, passEdit.text?.toString() ?: "")
        settings.write(
            AppSettings.Values(
                networkMode = (modeGroup.checkedRadioButtonId - 1).coerceIn(0, 2),
                ssid = ssidEdit.text?.toString() ?: "",
                demo = demoSwitch.isChecked,
                feet = distGroup.checkedButtonId != 2,
                fahrenheit = tempGroup.checkedButtonId == 2,
                textSize = when (textGroup.checkedButtonId) {
                    2 -> "large"
                    3 -> "xl"
                    else -> "normal"
                },
                beam = when (beamGroup.checkedButtonId) {
                    2 -> "40"
                    3 -> "125"
                    else -> "20"
                },
                deviceRangeM = rangeM.toFloat(),
                alarmM = alarmM.toFloat(),
                gainPercent = gainSlider.value.toInt(),
                surfaceClarity = (clarityGroup.checkedButtonId - 1).coerceIn(0, 3),
                noiseFilter = (noiseGroup.checkedButtonId - 1).coerceIn(0, 3),
                displayStyle = (styleGroup.checkedButtonId - 1).coerceIn(0, 1),
                fishMarkers = (fishGroup.checkedButtonId - 1).coerceIn(0, 2),
                keelOffsetM = keelField.baseValue().toFloat(),
                tempOffsetC = tempOffField.baseValue().toFloat(),
            ),
        )
        Units.load(settings.read())
    }

    /** Use platform-backed app preferences, with a recognizable native label for every option. */
    private fun showLanguagePicker() {
        val tags = listOf<String?>(null) + AppLanguage.choices.keys
        val labels = listOf(activity.getString(R.string.follow_device)) + AppLanguage.choices.values
        val selected = tags.indexOf(AppLanguage.selectedTag(activity)).coerceAtLeast(0)
        AppDialog.choice(activity, activity.getString(R.string.language_heading), labels, selected) { index ->
            // A locale-only refresh must not commit unfinished calibration or password drafts.
            changingLanguage = AppLanguage.changesConfiguration(activity, tags[index])
            AppLanguage.select(activity, tags[index])
            // A matching explicit language can persist without triggering recreation.
            languageButton.text = AppLanguage.choices[AppLanguage.selectedTag(activity)]
                ?: activity.getString(R.string.follow_device)
        }
    }

    /** Explicit state is needed because programmatic controls reuse local IDs in separate groups. */
    fun saveDraft(): Bundle = Bundle().apply {
        val fields = draftFields()
        fields.forEachIndexed { index, field -> putString("field_$index", field.text?.toString()) }
        val groups = listOf(distGroup, tempGroup, textGroup, beamGroup, clarityGroup, noiseGroup, styleGroup, fishGroup)
        groups.forEachIndexed { index, group -> putInt("group_$index", group.checkedButtonId) }
        putInt("mode", modeGroup.checkedRadioButtonId)
        putBoolean("demo", demoSwitch.isChecked)
        putFloat("gain", gainSlider.value)
    }

    /** Every free-text field, in the fixed order drafts store them. */
    private fun draftFields() = listOf(ssidEdit, passEdit, rangeField.edit, keelField.edit, tempOffField.edit, alarmField.edit)

    /** Build a [UnitField] with its own input and hint container; the caller adds its layout and shows a value. */
    private fun unitField(
        scale: UnitScale,
        inputType: Int,
        format: String,
        hint: (String) -> String,
        shows: (Double) -> Boolean,
        topMargin: Int = 0,
    ): UnitField {
        val edit = TextInputEditText(activity).apply { this.inputType = inputType }
        val layout = TextInputLayout(activity).apply {
            if (topMargin > 0) {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { this.topMargin = topMargin }
            }
            addView(edit)
        }
        return UnitField(layout, edit, scale, format, hint, shows)
    }

    /** Restore exact draft text, including blank or unfinished decimal entries, after recreation. */
    fun restoreDraft(draft: Bundle?) {
        if (draft == null) return
        // Units first: restoring them re-renders the unit fields, and the draft text
        // that follows was typed in those same restored units.
        val groups = listOf(distGroup, tempGroup, textGroup, beamGroup, clarityGroup, noiseGroup, styleGroup, fishGroup)
        groups.forEachIndexed { index, group -> group.check(draft.getInt("group_$index")) }
        val fields = draftFields()
        fields.forEachIndexed { index, field -> field.setText(draft.getString("field_$index")) }
        modeGroup.check(draft.getInt("mode"))
        demoSwitch.isChecked = draft.getBoolean("demo")
        gainSlider.value = draft.getFloat("gain", 100f)
    }
}
