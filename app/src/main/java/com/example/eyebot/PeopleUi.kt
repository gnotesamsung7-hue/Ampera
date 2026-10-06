package com.example.eyebot

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.text.InputType
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import java.util.Calendar

/**
 * Settings ▸ People: the household list and "Learn a new face".
 * Only face fingerprints (numbers) are stored, on this phone only.
 */
class PeopleUi(
    private val activity: Activity,
    private val registry: FaceRegistry,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        /** Start collecting face samples; [relearnId] = replace that person's samples. */
        fun startEnrollment(relearnId: Long?)
        fun peopleChanged()
        fun playMelody(person: FaceRegistry.Person)
        fun welcome(person: FaceRegistry.Person)
    }

    fun showList() {
        val people = registry.people().sortedBy { it.name.lowercase() }
        val labels = people.map { p ->
            buildString {
                append(p.name)
                if (p.guest) append("  (guest)")
                append("  ·  seen ").append(p.seenCount).append("x")
            }
        }.toTypedArray()
        val b = AlertDialog.Builder(activity)
            .setTitle("People Ampera knows (${people.size}/${FaceRegistry.MAX_PEOPLE})")
            .setPositiveButton("Learn a new face") { _, _ ->
                if (registry.size() >= FaceRegistry.MAX_PEOPLE) toast("Ampera already knows ${FaceRegistry.MAX_PEOPLE} people. Forget someone first.")
                else callbacks.startEnrollment(null)
            }
            .setNeutralButton("Close", null)
        if (people.isEmpty()) {
            b.setMessage("Nobody yet. Tap \"Learn a new face\", then look at the camera for a few seconds.\n\nOnly face fingerprints (numbers) are saved, on this phone only. No photos.")
        } else {
            b.setItems(labels) { _, which -> showPerson(people[which].id) }
            b.setNegativeButton("Forget everyone") { _, _ -> confirmForgetAll() }
        }
        b.show()
    }

    private fun showPerson(id: Long) {
        val p = registry.find(id) ?: return
        val options = arrayOf(
            "Play my melody",
            "New melody",
            "Rename",
            "Favourite eye colour",
            "Birthday" + if (p.birthMonth > 0) " (${p.birthMonth}/${p.birthDay})" else "",
            if (p.guest) "Keep (not a guest)" else "Make guest (forget after 7 days)",
            "Re-learn face",
            "Forget ${p.name}",
        )
        AlertDialog.Builder(activity)
            .setTitle(p.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> callbacks.playMelody(p)
                    1 -> { registry.update(id) { it.melodyVariant++ }; registry.find(id)?.let(callbacks::playMelody); callbacks.peopleChanged() }
                    2 -> askText("Rename", p.name) { n -> registry.update(id) { it.name = FaceRegistry.cleanName(n) }; callbacks.peopleChanged() }
                    3 -> pickColor(id)
                    4 -> pickBirthday(id)
                    5 -> { registry.update(id) { it.guest = !it.guest }; callbacks.peopleChanged() }
                    6 -> callbacks.startEnrollment(id)
                    7 -> confirm("Forget ${p.name}?", "Ampera will delete ${p.name}'s face fingerprints.") {
                        registry.remove(id); callbacks.peopleChanged()
                    }
                }
            }
            .setNegativeButton("Back") { _, _ -> showList() }
            .show()
    }

    /** After enrolment: ask the name (or just confirm a re-learn). */
    fun finishEnrollment(samples: List<FloatArray>, relearnId: Long?) {
        if (relearnId != null) {
            registry.replaceSamples(relearnId, samples)
            callbacks.peopleChanged()
            registry.find(relearnId)?.let { toast("Updated ${it.name}'s face") ; callbacks.welcome(it) }
            return
        }
        val dp = activity.resources.displayMetrics.density
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), 0)
        }
        val name = EditText(activity).apply {
            hint = "Name"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        val guest = CheckBox(activity).apply { text = "Guest (forget after 7 days)" }
        box.addView(name)
        box.addView(guest)
        AlertDialog.Builder(activity)
            .setTitle("Who is this?")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val p = registry.add(name.text.toString(), samples, guest.isChecked, System.currentTimeMillis())
                if (p == null) toast("Couldn't save (Ampera's list is full)")
                else { callbacks.peopleChanged(); callbacks.welcome(p) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pickColor(id: Long) {
        val names = listOf("Default") + QrCommand.NAMED_COLORS.keys.map { it.lowercase().replaceFirstChar(Char::uppercase) }
        AlertDialog.Builder(activity)
            .setTitle("Eye colour when they're around")
            .setItems(names.toTypedArray()) { _, which ->
                val c = if (which == 0) null else QrCommand.NAMED_COLORS.values.elementAt(which - 1)
                registry.update(id) { it.favoriteColor = c }
                callbacks.peopleChanged()
            }
            .show()
    }

    private fun pickBirthday(id: Long) {
        val p = registry.find(id) ?: return
        val c = Calendar.getInstance()
        val m = if (p.birthMonth > 0) p.birthMonth - 1 else c.get(Calendar.MONTH)
        val d = if (p.birthDay > 0) p.birthDay else c.get(Calendar.DAY_OF_MONTH)
        DatePickerDialog(activity, { _, _, month, day ->
            registry.update(id) { it.birthMonth = month + 1; it.birthDay = day }
            callbacks.peopleChanged()
        }, c.get(Calendar.YEAR), m, d).show()
    }

    private fun askText(title: String, initial: String, done: (String) -> Unit) {
        val input = EditText(activity).apply { setText(initial); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS }
        AlertDialog.Builder(activity).setTitle(title).setView(input)
            .setPositiveButton("OK") { _, _ -> done(input.text.toString()) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun confirmForgetAll() = confirm("Forget everyone?", "All face fingerprints will be deleted from this phone.") {
        registry.clear(); callbacks.peopleChanged()
    }

    private fun confirm(title: String, msg: String, yes: () -> Unit) {
        AlertDialog.Builder(activity).setTitle(title).setMessage(msg)
            .setPositiveButton("Forget") { _, _ -> yes() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun toast(msg: String) = android.widget.Toast.makeText(activity, msg, android.widget.Toast.LENGTH_SHORT).show()
}
