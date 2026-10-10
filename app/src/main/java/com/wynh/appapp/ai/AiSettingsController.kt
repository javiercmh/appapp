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
                    ai.cancelAll()
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
        render()
        activity.lifecycleScope.launch {
            while (dialog.isShowing) {
                delay(500)
                render()
            }
        }
    }
}
