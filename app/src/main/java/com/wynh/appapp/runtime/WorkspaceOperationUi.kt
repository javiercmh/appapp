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
    fun bind(activity: AppCompatActivity, onCompleted: (WorkspaceActionController.Completion) -> Unit = {}) {
        val controller = ViewModelProvider(activity)[WorkspaceActionController::class.java]
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                var progress: AlertDialog? = null
                var failure: AlertDialog? = null
                try {
                    controller.state.collect { state ->
                        if (state.busy) {
                            if (progress == null) progress = MaterialAlertDialogBuilder(activity).setTitle(state.message)
                                .setMessage(R.string.workspace_working).setCancelable(false).show()
                        } else {
                            progress?.dismiss(); progress = null
                            if (state.error) {
                                failure?.dismiss()
                                failure = MaterialAlertDialogBuilder(activity).setTitle(R.string.workspace_failed)
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
