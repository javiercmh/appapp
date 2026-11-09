package com.wynh.appapp.runtime

import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.wynh.appapp.R
import com.wynh.appapp.workspace.WorkspaceActionController
import kotlinx.coroutines.launch

object WorkspaceOperationUi {
    /**
     * Renders operation progress and failures and delivers each completion exactly once to
     * [onCompleted] on the first started screen, which may be a new instance after rotation.
     */
    fun bind(activity: AppCompatActivity, onCompleted: (WorkspaceActionController.Completion) -> Unit = {}) =
        bindWithFailures(activity, onCompleted = onCompleted)

    fun bindWithFailures(activity: AppCompatActivity, onFailure: (String) -> Boolean = { false }, onCompleted: (WorkspaceActionController.Completion) -> Unit = {}) {
        val controller = ViewModelProvider(activity)[WorkspaceActionController::class.java]
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                var progress: AlertDialog? = null
                var detailView: android.widget.TextView? = null
                var bar: com.google.android.material.progressindicator.LinearProgressIndicator? = null
                var failure: AlertDialog? = null
                try {
                    controller.state.collect { state ->
                        if (state.busy) {
                            if (progress == null) {
                                val body = activity.layoutInflater.inflate(R.layout.dialog_workspace_progress, null)
                                detailView = body.findViewById(R.id.workspace_progress_detail)
                                bar = body.findViewById(R.id.workspace_progress_bar)
                                progress = MaterialAlertDialogBuilder(activity).setTitle(state.message).setView(body).setCancelable(false).show()
                            }
                            detailView?.text = state.detail.ifEmpty { activity.getString(R.string.workspace_working) }
                            bar?.let { indicator ->
                                val fraction = state.fraction
                                if (fraction == null) { if (!indicator.isIndeterminate) { indicator.hide(); indicator.isIndeterminate = true; indicator.show() } }
                                else {
                                    // Switching modes needs the indicator hidden; afterwards the bar fills smoothly.
                                    if (indicator.isIndeterminate) { indicator.hide(); indicator.isIndeterminate = false; indicator.show() }
                                    indicator.setProgressCompat((fraction * indicator.max).toInt(), true)
                                }
                            }
                        } else {
                            progress?.dismiss(); progress = null; detailView = null; bar = null
                            if (state.error) {
                                failure?.dismiss()
                                if (!onFailure(state.message)) failure = MaterialAlertDialogBuilder(activity).setTitle(R.string.workspace_failed)
                                    .setMessage(state.message).setPositiveButton(android.R.string.ok, null).show()
                                controller.clear()
                            }
                            state.completions.forEach { completion ->
                                controller.acknowledge(completion.id)
                                if (completion.kind == WorkspaceActionController.Kind.UPDATED) {
                                    Toast.makeText(activity, "App code updated; user files preserved", Toast.LENGTH_LONG).show()
                                }
                                onCompleted(completion)
                            }
                        }
                    }
                } finally { progress?.dismiss(); failure?.dismiss() }
            }
        }
    }
}
