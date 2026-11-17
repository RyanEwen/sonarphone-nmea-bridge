package ca.dynamicsolutions.sonarbridge

import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.util.Locale

/**
 * How a Settings value converts between its stored base unit and the alternate
 * unit a user can choose to see it in.
 *
 * @property baseLabel unit label passed to the hint when the base unit is shown.
 * @property alternateLabel unit label passed to the hint when the alternate unit is shown.
 * @property alternatePerBase how many alternate units make one base unit.
 */
enum class UnitScale(val baseLabel: String, val alternateLabel: String, val alternatePerBase: Double) {
    /** Stored in metres, optionally shown in feet (exactly 0.3048 m per foot). */
    LENGTH("m", "ft", 1 / 0.3048),

    /** A temperature difference stored in °C, optionally shown in °F (no 32° shift). */
    TEMPERATURE_CHANGE("C", "F", 9.0 / 5.0),
}

/**
 * A numeric Settings field whose value lives in a base unit (metres or °C) and
 * is shown in whichever unit the Units toggles currently select.
 *
 * The unit the text is displayed in is always the unit it is parsed in: a unit
 * change re-renders the text rather than leaving it to be misread later. While
 * the text still matches what was rendered, [baseValue] returns the exact base
 * value, so switching units back and forth never accumulates rounding.
 *
 * @param layout the field's container, whose hint names the displayed unit.
 * @param edit the text input; drafts may hold unparseable text such as "-".
 * @param scale the conversion between the base and alternate unit.
 * @param format how a displayed value is printed, for example "%.1f".
 * @param hint builds the hint for a unit label ("ft", "m", "F" or "C").
 * @param shows whether a base value is displayed at all; hidden values render as
 *   blank, which saving reads as 0 (off, automatic or no offset).
 */
class UnitField(
    val layout: TextInputLayout,
    val edit: TextInputEditText,
    private val scale: UnitScale,
    private val format: String,
    private val hint: (unitLabel: String) -> String,
    private val shows: (Double) -> Boolean,
) {
    private var base = 0.0
    private var alternate = false

    /** The text this field last rendered from [base]; null after an unparseable unit switch. */
    private var rendered: String? = null

    /** Show [baseValue] in the alternate unit when [inAlternate] is true, otherwise in the base unit. */
    fun show(baseValue: Double, inAlternate: Boolean) {
        base = baseValue
        alternate = inAlternate
        render()
    }

    /**
     * Change the displayed unit, keeping the value the field currently means.
     * Edited text is parsed in the old unit and rendered in the new one; text that
     * does not parse (an unfinished draft) is left as typed.
     */
    fun switchUnit(inAlternate: Boolean) {
        if (inAlternate == alternate) return
        val text = currentText()
        if (text == rendered) {
            alternate = inAlternate
            render()
            return
        }
        val parsed = text.toDoubleOrNull()
        if (parsed != null) {
            base = toBase(parsed)
            alternate = inAlternate
            render()
        } else {
            alternate = inAlternate
            rendered = null
            layout.hint = hint(unitLabel())
        }
    }

    /**
     * The value to save, in the base unit: the exact value when the text is still
     * what was rendered, otherwise the text read in the displayed unit, or 0 when
     * it is blank or unparseable.
     */
    fun baseValue(): Double {
        val text = currentText()
        if (text == rendered) return base
        return text.toDoubleOrNull()?.let(::toBase) ?: 0.0
    }

    private fun render() {
        val text = if (shows(base)) String.format(Locale.US, format, fromBase(base)) else ""
        edit.setText(text)
        rendered = text
        layout.hint = hint(unitLabel())
    }

    private fun currentText() = edit.text?.toString() ?: ""

    private fun unitLabel() = if (alternate) scale.alternateLabel else scale.baseLabel

    private fun fromBase(value: Double) = if (alternate) value * scale.alternatePerBase else value

    private fun toBase(value: Double) = if (alternate) value / scale.alternatePerBase else value
}
