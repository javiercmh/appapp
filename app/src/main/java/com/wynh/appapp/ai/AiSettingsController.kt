package com.wynh.appapp.ai

import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.activity.ComponentActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.wynh.appapp.R
import com.google.android.material.textfield.TextInputLayout
import com.wynh.appapp.ai.auth.AuthPhase
import com.wynh.appapp.ai.network.ApiFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object AiSettingsController {
    /** Whether the AI card is expanded; kept for the process so reopening Settings keeps the choice. */
    private var expanded = false

    /** One service row in the AI card. A service is listed only once it is added (or being added). */
    private class Service(
        val id: String,
        val name: String,
        val group: View,
        val added: () -> Boolean,
        val start: () -> Unit
    )

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
        val pending = mutableSetOf<String>()
        val services = listOf(
            Service("chatgpt", "ChatGPT", dialog.findViewById(R.id.settings_group_chatgpt), {
                val state = ai.auth.state.value
                state.accounts.isNotEmpty() || state.phase != AuthPhase.IDLE || "chatgpt" in pending
            }) { signIn() },
            bindOpenRouter(activity, dialog),
            bindCustomEndpoint(activity, dialog)
        )
        bindShell(activity, dialog, services, pending)
        render()
        activity.lifecycleScope.launch {
            while (dialog.isShowing) {
                delay(500)
                render()
            }
        }
    }

    private fun bindCustomEndpoint(activity: ComponentActivity, dialog: Dialog): Service {
        val ai = (activity.application as AppAiApplication).ai
        val status = dialog.findViewById<TextView>(R.id.settings_tv_custom_status)
        val connection = dialog.findViewById<TextView>(R.id.settings_tv_custom_connection)
        val checkButton = dialog.findViewById<MaterialButton>(R.id.settings_btn_custom_set)
        val more = dialog.findViewById<ImageButton>(R.id.settings_btn_custom_more)
        var busy = false
        var feedback: String? = null
        fun render() {
            checkButton.text = if (busy) "Checking…" else "Check connection"
            checkButton.isEnabled = !busy && ai.customConnected
            more.isEnabled = !busy && !ai.customRestoring
            connection.text = when {
                ai.customRestoring -> "Connecting…"
                ai.customConnected -> "Connected"
                else -> "Not connected"
            }
            connection.setTextColor(ContextCompat.getColor(activity,
                if (ai.customConnected) R.color.accent_green else R.color.on_surface_muted))
            status.text = when {
                ai.customRestoring -> "Restoring connection…"
                ai.customConnected -> listOfNotNull(ai.customUrl, feedback).joinToString("\n")
                else -> feedback ?: ai.customMessage ?: ""
            }
        }
        fun runOperation(message: String, operation: suspend () -> String?) {
            busy = true; feedback = message; render()
            activity.lifecycleScope.launch {
                try { feedback = operation() }
                catch (e: Exception) { feedback = e.message ?: "Custom endpoint operation failed." }
                finally { busy = false; render() }
            }
        }
        fun openEntry() {
            val editing = ai.customConnected
            val urlField = textField(activity, password = false).apply { setText(ai.customUrl.orEmpty()) }
            val keyField = textField(activity, password = true)
            val urlLayout = fieldLayout(activity, "Base URL (https://…/v1)", urlField)
            val keyLayout = fieldLayout(activity, if (editing) "API key (leave empty to keep the current key)" else "API key", keyField, password = true)
            val form = android.widget.LinearLayout(activity).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                val pad = (20 * activity.resources.displayMetrics.density).toInt()
                setPadding(pad, 0, pad, 0)
                addView(urlLayout)
                addView(keyLayout, android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (8 * activity.resources.displayMetrics.density).toInt() })
            }
            showVerifyDialog(activity,
                title = if (editing) "Edit custom endpoint" else "Add custom endpoint",
                message = "Works with any OpenAI-compatible service. The URL and key are verified by listing the service's models, then stored encrypted on this device.",
                form = form,
                errorField = { e -> if ((e as? ApiFailure)?.kind == ApiFailure.Kind.AUTHENTICATION) keyLayout else urlLayout },
                verify = {
                    val count = ai.saveCustomEndpoint(urlField.text?.toString().orEmpty(), keyField.text?.toString().orEmpty())
                    preferNewlyAdded(ai, "custom")
                    "Verified · $count models available."
                },
                onSaved = { feedback = it; render() },
                onClosed = { keyField.text?.clear() })
        }
        checkButton.setOnClickListener {
            runOperation("Checking connection…") { "Verified · ${ai.checkCustomEndpoint()} models available." }
        }
        more.setOnClickListener {
            PopupMenu(activity, more).apply {
                menu.add(0, 1, 0, "Edit endpoint")
                menu.add(0, 2, 1, "Remove custom endpoint")
                setOnMenuItemClickListener { item ->
                    if (item.itemId == 1) openEntry()
                    else MaterialAlertDialogBuilder(activity).setTitle("Remove custom endpoint?")
                        .setMessage("Cancels unfinished requests to it. Completed results remain available for saving. The key is not revoked at the service.")
                        .setNegativeButton("Cancel", null).setPositiveButton("Remove") { _, _ ->
                            runOperation("Removing…") { ai.removeCustomEndpoint(); null }
                        }.show()
                    true
                }
                show()
            }
        }
        render()
        activity.lifecycleScope.launch { while (dialog.isShowing) { delay(500); render() } }
        // Listed only once saved: setup and its errors happen in the dialog, so a failed attempt adds nothing.
        return Service("custom", "Custom endpoint", dialog.findViewById(R.id.settings_group_custom),
            { ai.customConnected || ai.customRestoring || busy }) { openEntry() }
    }

    private fun bindOpenRouter(activity: ComponentActivity, dialog: Dialog): Service {
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
            keyButton.text = if (busy) "Checking…" else "Check connection"
            keyButton.isEnabled = !busy && ai.openRouterConnected
            more.isEnabled = !busy && !ai.openRouterRestoring
            connection.text = when {
                ai.openRouterRestoring -> "Connecting…"
                ai.openRouterConnected -> "Connected"
                else -> "Not connected"
            }
            connection.setTextColor(ContextCompat.getColor(activity,
                if (ai.openRouterConnected) R.color.accent_green else R.color.on_surface_muted))
            paid.isEnabled = !busy && !ai.openRouterRestoring
            status.text = feedback ?: when {
                ai.openRouterRestoring -> "Restoring connection…"
                ai.openRouterConnected -> "Key saved. Available to your apps."
                else -> ai.openRouterMessage.orEmpty()
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
        fun runOperation(message: String, operation: suspend () -> String?) {
            busy = true; feedback = message; render()
            activity.lifecycleScope.launch {
                try { feedback = operation() }
                catch (e: Exception) { feedback = e.message ?: "OpenRouter operation failed." }
                finally { busy = false; render() }
            }
        }
        fun openEntry() {
            val editing = ai.openRouterConnected
            val field = textField(activity, password = true)
            val input = fieldLayout(activity, "OpenRouter API key", field, password = true).apply {
                val pad = (20 * resources.displayMetrics.density).toInt()
                setPadding(pad, 0, pad, 0)
            }
            showVerifyDialog(activity,
                title = if (editing) "Replace OpenRouter key" else "Add OpenRouter",
                message = "Paste a key from your OpenRouter account. It is verified before saving, without generating text or spending credits, and stored securely on this device.",
                form = input,
                errorField = { input },
                neutral = "Get a key" to {
                    try {
                        activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://openrouter.ai/settings/keys")))
                    } catch (_: android.content.ActivityNotFoundException) {
                        input.error = "Install a browser to open OpenRouter."
                    }
                },
                verify = {
                    ai.saveOpenRouterKey(field.text?.toString().orEmpty())
                    preferNewlyAdded(ai, "openrouter")
                    "Key verified and saved."
                },
                onSaved = { feedback = it; render() },
                onClosed = { field.text?.clear() })
        }
        keyButton.setOnClickListener {
            runOperation("Checking connection…") { ai.checkOpenRouter(); "Connection verified." }
        }
        paid.isChecked = ai.allowPaidModels
        paid.setOnCheckedChangeListener { _, enabled ->
            ai.setAllowPaid(enabled)
            feedback = if (enabled) "Paid models enabled. Generation can spend OpenRouter credits." else "Only explicitly free models are available."
            render()
        }
        val limitDropdown = dialog.findViewById<com.google.android.material.textfield.MaterialAutoCompleteTextView>(R.id.settings_dropdown_ai_start_limit)
        val limitStatus = dialog.findViewById<TextView>(R.id.settings_tv_ai_start_limit)
        fun renderLimit() {
            val limit = ai.startLimit
            limitDropdown.setText(AiStartRateLimiter.label(limit), false)
            limitStatus.text = if (limit == AiStartRateLimiter.UNLIMITED)
                "Apps can start any number of AI requests. A faulty app could use your plan or credits quickly."
            else "Each app can start up to $limit AI requests per minute. Extra starts fail until the minute passes."
        }
        limitDropdown.setSimpleItems(AiStartRateLimiter.CHOICES.map(AiStartRateLimiter::label).toTypedArray())
        renderLimit()
        limitDropdown.setOnItemClickListener { _, _, index, _ ->
            AiStartRateLimiter.CHOICES.getOrNull(index)?.let(ai::setStartLimit)
            renderLimit()
        }
        exposureDropdown.setOnItemClickListener { _, _, index, _ ->
            val selected = exposureChoices.getOrNull(index) ?: return@setOnItemClickListener
            if (ai.registry.selectableExposures().none { it.id == selected.id }) { render(); return@setOnItemClickListener }
            ai.setExposure(selected.id)
            render()
        }
        more.setOnClickListener {
            PopupMenu(activity, more).apply {
                menu.add(0, 1, 0, "Replace key")
                menu.add(0, 2, 1, "Remove saved key")
                setOnMenuItemClickListener { item ->
                    if (item.itemId == 1) openEntry()
                    else MaterialAlertDialogBuilder(activity).setTitle("Remove saved OpenRouter key?")
                        .setMessage("Cancels unfinished OpenRouter requests. Completed results remain available for saving. The key is not revoked at OpenRouter.")
                        .setNegativeButton("Cancel", null).setPositiveButton("Remove") { _, _ ->
                            runOperation("Removing key…") { ai.removeOpenRouterKey(); null }
                        }.show()
                    true
                }
                show()
            }
        }
        render()
        activity.lifecycleScope.launch { while (dialog.isShowing) { delay(500); render() } }
        return Service("openrouter", "OpenRouter", dialog.findViewById(R.id.settings_group_openrouter),
            { ai.openRouterConnected || ai.openRouterRestoring || busy }) { openEntry() }
    }

    private fun textField(activity: ComponentActivity, password: Boolean) =
        com.google.android.material.textfield.TextInputEditText(activity).apply {
            // Single-line first: setSingleLine() replaces the transformation method, which would
            // otherwise undo the password masking that setInputType() installs.
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or if (password)
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD else android.text.InputType.TYPE_TEXT_VARIATION_URI
            if (password) {
                transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
                typeface = android.graphics.Typeface.DEFAULT
            }
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        }

    private fun fieldLayout(activity: ComponentActivity, hint: String, field: View, password: Boolean = false) =
        TextInputLayout(activity).apply {
            this.hint = hint
            if (password) endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
            addView(field)
        }

    /**
     * Entry dialog that verifies before closing. "Verify and save" keeps the dialog open while it checks;
     * a failure is shown on the field it concerns so the user can correct it in place. Only a verified,
     * saved service closes the dialog. Cancel (or dismissing) abandons an in-flight check.
     */
    private fun showVerifyDialog(
        activity: ComponentActivity,
        title: String,
        message: String,
        form: View,
        errorField: (Exception) -> TextInputLayout,
        neutral: Pair<String, () -> Unit>? = null,
        verify: suspend () -> String,
        onSaved: (String) -> Unit,
        onClosed: () -> Unit
    ) {
        val builder = MaterialAlertDialogBuilder(activity).setTitle(title).setMessage(message).setView(form)
            .setNegativeButton("Cancel", null).setPositiveButton("Verify and save", null)
        if (neutral != null) builder.setNeutralButton(neutral.first, null)
        val entry = builder.create()
        var job: Job? = null
        var lastError: TextInputLayout? = null
        entry.setOnDismissListener { job?.cancel(); onClosed() }
        entry.setOnShowListener {
            val save = entry.getButton(AlertDialog.BUTTON_POSITIVE)
            if (neutral != null) entry.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { neutral.second() }
            save.setOnClickListener {
                lastError?.error = null
                save.isEnabled = false
                save.text = "Verifying…"
                job = activity.lifecycleScope.launch {
                    try {
                        val result = verify()
                        job = null
                        entry.dismiss()
                        onSaved(result)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        lastError = errorField(e).apply { error = e.message ?: "Verification failed." }
                        save.isEnabled = true
                        save.text = "Verify and save"
                    }
                }
            }
        }
        entry.show()
    }

    /** After the user adds a service, make it the one subapps see if the current choice is not connected. */
    private fun preferNewlyAdded(ai: AppAiManager, id: String) {
        if (!ai.registry.exposed().connected) ai.setExposure(id)
    }

    /** Collapsible card shell: summary header, the list of added services and the "Add AI service" menu. */
    private fun bindShell(activity: ComponentActivity, dialog: Dialog, services: List<Service>, pending: MutableSet<String>) {
        val ai = (activity.application as AppAiApplication).ai
        val header = dialog.findViewById<View>(R.id.settings_ai_header)
        val summary = dialog.findViewById<TextView>(R.id.settings_tv_ai_summary)
        val chevron = dialog.findViewById<ImageView>(R.id.settings_iv_ai_chevron)
        val body = dialog.findViewById<View>(R.id.settings_ai_body)
        val add = dialog.findViewById<MaterialButton>(R.id.settings_btn_ai_add)
        val exposure = dialog.findViewById<View>(R.id.settings_group_exposure)
        fun render() {
            val shown = services.filter { it.added() }
            services.forEach { it.group.visibility = if (it in shown) View.VISIBLE else View.GONE }
            add.visibility = if (shown.size < services.size) View.VISIBLE else View.GONE
            add.text = "Add another service"
            val choices = ai.registry.selectableExposures()
            exposure.visibility = if (choices.size >= 2 || (choices.isNotEmpty() && !ai.registry.exposed().connected))
                View.VISIBLE else View.GONE
            // With nothing added there is nothing to expand: the header shows "+" and opens the Add menu.
            val open = expanded && shown.isNotEmpty()
            body.visibility = if (open) View.VISIBLE else View.GONE
            chevron.setImageResource(when {
                shown.isEmpty() -> R.drawable.ic_add
                open -> R.drawable.ic_arrow_up
                else -> R.drawable.ic_arrow_down
            })
            val connected = ai.registry.providers.filter { it.connected() }.map { it.name }
            val waiting = shown.filter { s -> ai.registry.providers.none { it.id == s.id && it.connected() } }.map { it.name }
            val exposed = ai.registry.exposed()
            val restoring = ai.openRouterRestoring || ai.customRestoring || ai.auth.state.value.phase == AuthPhase.RESTORING
            summary.text = when {
                restoring -> "Checking saved services…"
                shown.isEmpty() -> "No services added"
                connected.isEmpty() -> "${waiting.joinToString()} · not connected yet"
                !exposed.connected -> "${connected.joinToString()} · choose what apps see"
                connected.size == 1 -> "${connected.single()} · available to your apps"
                ai.exposure == "combined" -> "${connected.joinToString()} · apps see all"
                else -> "${connected.joinToString()} · apps see ${exposed.name}"
            } + if (connected.isNotEmpty() && waiting.isNotEmpty()) "\n${waiting.joinToString()} needs attention" else ""
            header.contentDescription = "AI services, ${summary.text}. " + when {
                shown.isEmpty() -> "Add a service"
                open -> "Collapse"
                else -> "Expand"
            }
        }
        fun showAddMenu(anchor: View) {
            PopupMenu(activity, anchor).apply {
                services.filterNot { it.added() }.forEachIndexed { index, service -> menu.add(0, index, index, service.name) }
                setOnMenuItemClickListener { item ->
                    val service = services.filterNot { it.added() }.getOrNull(item.itemId) ?: return@setOnMenuItemClickListener true
                    pending += service.id
                    expanded = true
                    render()
                    service.start()
                    true
                }
                show()
            }
        }
        header.setOnClickListener {
            if (services.none { it.added() }) showAddMenu(chevron)
            else { expanded = !expanded; render() }
        }
        add.setOnClickListener { showAddMenu(add) }
        render()
        activity.lifecycleScope.launch { while (dialog.isShowing) { delay(500); render() } }
    }
}
