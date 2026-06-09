package com.fzi.acousticscene.ui.entry

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.fzi.acousticscene.R
import com.google.android.material.button.MaterialButton

/**
 * Hard-coded developer-access gate behind the Configuration tile on the mode
 * picker. The password is `1234` (study setup) — this is a UX gate, not
 * real auth, so we keep it in code.
 *
 * Right password → navigate to Config Welcome and pop only this fragment off
 * the back stack so Mode Select stays underneath. From Welcome the user can
 * press the Back chevron (or system back) to return to Mode Select.
 * Wrong password → inline error, field clears, eye toggles back to masked.
 */
class PasswordFragment : Fragment(R.layout.fragment_password) {

    companion object {
        private const val DEV_PASSWORD = "1234"
    }

    private lateinit var input: EditText
    private lateinit var reveal: ImageButton
    private lateinit var error: TextView
    private var revealed = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        input = view.findViewById(R.id.passwordInput)
        reveal = view.findViewById(R.id.passwordRevealButton)
        error = view.findViewById(R.id.passwordError)
        val unlock = view.findViewById<MaterialButton>(R.id.passwordUnlockButton)
        val back = view.findViewById<View>(R.id.passwordBackButton)

        back.setOnClickListener { findNavController().popBackStack() }
        // Hardware back mirrors the chevron — returns to Mode Select.
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    findNavController().popBackStack()
                }
            }
        )

        // Reset the wrong-password warning the moment the user touches the input
        // again — otherwise it stays loud while they're trying to correct.
        input.doOnTextChanged { _, _, _, _ -> error.visibility = View.GONE }

        // Submit also fires on the keyboard's Done action so the user doesn't
        // have to dismiss the keyboard first.
        input.imeOptions = EditorInfo.IME_ACTION_DONE
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                unlock.performClick()
                true
            } else false
        }

        unlock.setOnClickListener { trySubmit() }

        reveal.setOnClickListener { toggleReveal() }
    }

    private fun trySubmit() {
        val entered = input.text?.toString().orEmpty()
        if (entered == DEV_PASSWORD) {
            // Pop only this fragment off the back stack so Mode Select stays
            // underneath Welcome — from there a Back tap returns the user to
            // the mode picker rather than exiting the app.
            findNavController().navigate(R.id.action_password_to_welcome)
        } else {
            error.visibility = View.VISIBLE
            input.text?.clear()
            if (revealed) toggleReveal()
        }
    }

    private fun toggleReveal() {
        revealed = !revealed
        val selection = input.selectionEnd.coerceAtLeast(0)
        input.inputType = if (revealed) {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        } else {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        // Restore the cursor — the inputType swap blows it away otherwise.
        input.setSelection(selection.coerceAtMost(input.text?.length ?: 0))
        reveal.setImageResource(if (revealed) R.drawable.ic_eye_off else R.drawable.ic_eye)
        reveal.contentDescription = getString(
            if (revealed) R.string.password_hide else R.string.password_show
        )
    }
}
