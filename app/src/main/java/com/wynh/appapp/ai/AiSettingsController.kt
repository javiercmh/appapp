package com.wynh.appapp.ai

import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.wynh.appapp.R
import com.wynh.appapp.ai.auth.AuthPhase
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object AiSettingsController {
    fun bind(activity: ComponentActivity, dialog: Dialog) {
        val ai = (activity.application as AppAiApplication).ai
        val status = dialog.findViewById<TextView>(R.id.settings_tv_ai_status) ?: return
        val connection = dialog.findViewById<TextView>(R.id.settings_tv_ai_connection)
        val primary = dialog.findViewById<MaterialButton>(R.id.settings_btn_ai_signin)
        val more = dialog.findViewById<ImageButton>(R.id.settings_btn_ai_more)
        var checking = false
        var feedback: String? = null

        fun render() {
            val state = ai.auth.state.value
            val active = state.accounts.firstOrNull { it.clientId == state.activeClientId }
            val busy = state.phase != AuthPhase.IDLE || checking
            connection.text = when {
                checking -> "Checking connection…"
                state.phase != AuthPhase.IDLE -> "Connecting…"
                active?.canInfer == true -> "Connected"
                active?.signedIn == true -> "Access unavailable"
                active != null -> "Sign-in required"
                else -> "Not connected"
            }
            connection.setTextColor(ContextCompat.getColor(activity,
                if (active?.canInfer == true && !busy) R.color.accent_green else R.color.on_surface_muted))
            status.text = feedback ?: state.message ?: when {
                state.phase == AuthPhase.RESTORING -> "Restoring your saved connection…"
                active?.canInfer == true -> "${active.label}\nAvailable to all your apps."
                active?.signedIn == true -> "Signed in, but ChatGPT plan usage is not enabled."
                active != null -> "Sign in again to reconnect ${active.label}."
                else -> "Connect your account to use AI in your apps."
            }
            primary.text = when {
                checking -> "Checking…"
                state.phase != AuthPhase.IDLE -> "Connecting…"
                active?.canInfer == true -> "Check connection"
                active != null -> "Reconnect ChatGPT"
                else -> "Connect ChatGPT"
            }
            primary.isEnabled = !busy
            more.isEnabled = !busy
        }

        fun signIn() {
            feedback = null
            val state = ai.auth.state.value
            val current = state.accounts.firstOrNull { it.clientId == state.activeClientId }
                ?: state.accounts.singleOrNull()
            ai.beginSignIn(current?.clientId)
            render()
            activity.lifecycleScope.launch {
                try {
                    val url = ai.nextAuthorizationUrl()
                    activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (e: Exception) {
                    feedback = e.message ?: "Could not open ChatGPT sign-in."
                }
                render()
            }
        }

        fun checkConnection() {
            checking = true
            feedback = "Loading available models…"
            render()
            activity.lifecycleScope.launch {
                try {
                    val model = ai.models().firstOrNull() ?: error("No models are available for this account.")
                    feedback = "Checking ${model.displayName}…"
                    render()
                    ai.check(model.slug)
                    feedback = "Connection verified with ${model.displayName}."
                } catch (e: Exception) {
                    feedback = e.message ?: "The connection check failed."
                } finally {
                    checking = false
                    render()
                }
            }
        }

        fun recoverCredentials() {
            MaterialAlertDialogBuilder(activity)
                .setTitle("Reset saved ChatGPT sign-in?")
                .setMessage("This clears AppApp’s saved ChatGPT accounts and tokens from this device. It does not revoke access at ChatGPT.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Reset credentials") { _, _ -> activity.lifecycleScope.launch {
                    ai.cancelProvider("chatgpt")
                    ai.inference.clearCatalog()
                    feedback = runCatching { ai.auth.resetLocalCredentials() }
                        .fold({ "Saved credentials removed. You can connect again." }, { it.message })
                    render()
                } }.show()
        }

        primary.setOnClickListener {
            val state = ai.auth.state.value
            val active = state.accounts.firstOrNull { it.clientId == state.activeClientId }
            if (active?.canInfer == true) checkConnection() else signIn()
        }
        more.setOnClickListener {
            val state = ai.auth.state.value
            val active = state.accounts.firstOrNull { it.clientId == state.activeClientId }
            PopupMenu(activity, more).apply {
                if (active?.signedIn == true) menu.add(0, 2, 0, "Sign out")
                menu.add(0, 3, 1, "Reset saved sign-in")
                setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        2 -> activity.lifecycleScope.launch {
                            feedback = runCatching { ai.signOut() }
                                .fold({ it }, { it.message ?: "Could not sign out." })
                            render()
                        }
                        3 -> recoverCredentials()
                    }
                    true
                }
                show()
            }
        }
        bindOpenRouter(activity, dialog)
        render()
        activity.lifecycleScope.launch {
            while (dialog.isShowing) {
                delay(500)
                render()
            }
        }
    }

    private fun bindOpenRouter(activity: ComponentActivity, dialog: Dialog) {
        val ai = (activity.application as AppAiApplication).ai
        val status = dialog.findViewById<TextView>(R.id.settings_tv_openrouter_status)
        val keyButton = dialog.findViewById<MaterialButton>(R.id.settings_btn_openrouter_key)
        val connection = dialog.findViewById<TextView>(R.id.settings_tv_openrouter_connection)
        val more = dialog.findViewById<ImageButton>(R.id.settings_btn_openrouter_more)
        val paid = dialog.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.settings_switch_openrouter_paid)
        val exposureInput = dialog.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.settings_input_ai_exposure)
        val exposureDropdown = dialog.findViewById<com.google.android.material.textfield.MaterialAutoCompleteTextView>(R.id.settings_dropdown_ai_exposure)
        val exposureStatus = dialog.findViewById<TextView>(R.id.settings_tv_ai_exposure)
        var exposureChoices = emptyList<ExposedProvider>()
        var busy = false
        var feedback: String? = null
        fun render() {
            keyButton.text = if (ai.openRouterConnected) "Replace OpenRouter key" else "Add OpenRouter key"
            keyButton.isEnabled = !busy && !ai.openRouterRestoring
            more.isEnabled = !busy && !ai.openRouterRestoring
            connection.text = when {
                busy || ai.openRouterRestoring -> "Connecting…"
                ai.openRouterConnected -> "Connected"
                else -> "Not connected"
            }
            connection.setTextColor(ContextCompat.getColor(activity,
                if (ai.openRouterConnected && !busy) R.color.accent_green else R.color.on_surface_muted))
            paid.isEnabled = !busy && !ai.openRouterRestoring
            status.text = feedback ?: ai.openRouterMessage ?: when {
                ai.openRouterRestoring -> "Restoring connection…"
                ai.openRouterConnected -> "Available to subapps according to your exposure setting."
                else -> "Add a key from your OpenRouter account."
            }
            val choices = ai.registry.selectableExposures()
            if (choices != exposureChoices) {
                exposureDropdown.dismissDropDown()
                exposureChoices = choices
                exposureDropdown.setSimpleItems(choices.map { it.name }.toTypedArray())
            }
            exposureInput.isEnabled = choices.isNotEmpty()
            val current = ai.registry.exposed()
            val selected = when {
                choices.isEmpty() -> "No providers connected"
                ai.exposure == "combined" && choices.none { it.id == "combined" } -> "Combined (one provider connected)"
                !current.connected -> "${current.name} (not connected)"
                ai.exposure == "combined" -> "Combined"
                else -> current.name
            }
            if (exposureDropdown.text.toString() != selected) exposureDropdown.setText(selected, false)
            exposureStatus.text = when {
                choices.isEmpty() -> "Connect a provider above to make AI available to subapps."
                !ai.registry.exposed().connected -> "Not connected. Set up the selected provider above."
                ai.exposure == "combined" -> "Subapps see one model list from connected providers. Each model is labeled by source."
                else -> "Subapps see only ${ai.registry.exposed().name}. Other connections stay saved."
            }
        }
        fun runOperation(message: String, operation: suspend () -> String) {
            busy = true; feedback = message; render()
            activity.lifecycleScope.launch {
                try { feedback = operation() }
                catch (e: Exception) { feedback = e.message ?: "OpenRouter operation failed." }
                finally { busy = false; render() }
            }
        }
        keyButton.setOnClickListener {
            val field = com.google.android.material.textfield.TextInputEditText(activity).apply {
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
                imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                isSingleLine = true
            }
            val input = com.google.android.material.textfield.TextInputLayout(activity).apply {
                hint = "OpenRouter API key"
                val pad = (20 * resources.displayMetrics.density).toInt()
                setPadding(pad, 0, pad, 0)
                addView(field)
            }
            val entry = MaterialAlertDialogBuilder(activity).setTitle(keyButton.text)
                .setMessage("Stored securely on this device. Verification does not generate text or spend inference credits.")
                .setView(input).setNeutralButton("Get a key", null)
                .setNegativeButton("Cancel", null).setPositiveButton("Verify and save", null).create()
            entry.setOnShowListener {
                entry.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    try {
                        activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://openrouter.ai/settings/keys")))
                    } catch (_: android.content.ActivityNotFoundException) {
                        input.error = "Install a browser to open OpenRouter."
                    }
                }
                entry.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val candidate = field.text?.toString().orEmpty()
                    field.text?.clear()
                    entry.dismiss()
                    runOperation("Verifying key…") { ai.saveOpenRouterKey(candidate); "Key verified and saved." }
                }
            }
            entry.show()
        }
        paid.isChecked = ai.allowPaidModels
        paid.setOnCheckedChangeListener { _, enabled ->
            ai.setAllowPaid(enabled)
            feedback = if (enabled) "Paid models enabled. Generation can spend OpenRouter credits." else "Only explicitly free models are available."
            render()
        }
        exposureDropdown.setOnItemClickListener { _, _, index, _ ->
            val selected = exposureChoices.getOrNull(index) ?: return@setOnItemClickListener
            if (ai.registry.selectableExposures().none { it.id == selected.id }) { render(); return@setOnItemClickListener }
            ai.setExposure(selected.id)
            render()
        }
        more.setOnClickListener {
            PopupMenu(activity, more).apply {
                menu.add("Remove saved key")
                setOnMenuItemClickListener {
                    MaterialAlertDialogBuilder(activity).setTitle("Remove saved OpenRouter key?")
                        .setMessage("Cancels unfinished OpenRouter requests. Completed results remain available for saving. The key is not revoked at OpenRouter.")
                        .setNegativeButton("Cancel", null).setPositiveButton("Remove") { _, _ ->
                            runOperation("Removing key…") { ai.removeOpenRouterKey(); "Saved key removed." }
                        }.show()
                    true
                }
                show()
            }
        }
        render()
        activity.lifecycleScope.launch { while (dialog.isShowing) { delay(500); render() } }
    }

}
