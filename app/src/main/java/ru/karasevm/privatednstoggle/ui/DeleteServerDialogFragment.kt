package ru.karasevm.privatednstoggle.ui

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import ru.karasevm.privatednstoggle.R


class DeleteServerDialogFragment : DialogFragment() {
    // Stored in the arguments so the fragment survives recreation (rotation, process death)
    private val serverId: Int by lazy { requireArguments().getInt(ARG_ID) }

    // Use this instance of the interface to deliver action events
    private lateinit var listener: NoticeDialogListener

    /* The activity that creates an instance of this dialog fragment must
     * implement this interface in order to receive event callbacks.
     * Each method passes the DialogFragment in case the host needs to query it. */
    interface NoticeDialogListener {
        fun onDeleteDialogPositiveClick(id: Int)
    }

    // Override the Fragment.onAttach() method to instantiate the NoticeDialogListener
    override fun onAttach(context: Context) {
        super.onAttach(context)
        // Verify that the host activity implements the callback interface
        try {
            // Instantiate the NoticeDialogListener so we can send events to the host
            listener = context as NoticeDialogListener
        } catch (e: ClassCastException) {
            // The activity doesn't implement the interface, throw exception
            throw ClassCastException(
                (context.toString() +
                        " must implement NoticeDialogListener")
            )
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return activity?.let {
            val builder = MaterialAlertDialogBuilder(it)

            builder.setTitle(R.string.delete_question)
                .setMessage(R.string.delete_message)
                .setPositiveButton(
                    R.string.delete
                ) { _, _ ->
                    listener.onDeleteDialogPositiveClick(serverId)
                }
                .setNegativeButton(
                    R.string.cancel
                ) { _, _ ->
                    dialog?.cancel()
                }
            // Create the AlertDialog object and return it
            builder.create()
        } ?: throw IllegalStateException("Activity cannot be null")
    }

    companion object {
        private const val ARG_ID = "id"

        /**
         * Creates the dialog for deleting a server
         * @param id The server id
         */
        fun newInstance(id: Int) = DeleteServerDialogFragment().apply {
            arguments = Bundle().apply { putInt(ARG_ID, id) }
        }
    }
}
