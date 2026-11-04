package ru.karasevm.privatednstoggle.ui

import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import ru.karasevm.privatednstoggle.R
import ru.karasevm.privatednstoggle.databinding.DialogAddBinding
import ru.karasevm.privatednstoggle.model.DnsServer
import ru.karasevm.privatednstoggle.util.ServerValidation


class AddServerDialogFragment : DialogFragment() {
    // The server being edited, or null when adding a new one. Stored in the arguments so the
    // fragment survives recreation (rotation, process death).
    private val dnsServer: DnsServer? by lazy {
        val args = requireArguments()
        if (!args.containsKey(ARG_ID)) {
            null
        } else {
            DnsServer(
                id = args.getInt(ARG_ID),
                server = args.getString(ARG_SERVER).orEmpty(),
                label = args.getString(ARG_LABEL).orEmpty(),
                enabled = args.getBoolean(ARG_ENABLED, true)
            )
        }
    }
    // Use this instance of the interface to deliver action events
    private lateinit var listener: NoticeDialogListener

    private var _binding: DialogAddBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    /* The activity that creates an instance of this dialog fragment must
     * implement this interface in order to receive event callbacks.
     * Each method passes the DialogFragment in case the host needs to query it. */
    interface NoticeDialogListener {
        fun onAddDialogPositiveClick(label: String?, server: String)
        fun onUpdateDialogPositiveClick(id: Int, server: String, label: String?, enabled: Boolean)
        fun onDeleteItemClicked(id: Int)
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

    override fun onCreateDialog(
        savedInstanceState: Bundle?
    ): Dialog {
        return activity?.let {
            val builder = MaterialAlertDialogBuilder(it)
            // Get the layout inflater
            val inflater = requireActivity().layoutInflater
            _binding = DialogAddBinding.inflate(inflater)

            val view = binding.root
            val dnsServer = dnsServer
            // Inflate and set the layout for the dialog
            // Pass null as the parent view because its going in the dialog layout
            if (dnsServer != null) {
                binding.editTextServerHint.setText(dnsServer.label)
                binding.editTextServerAddr.setText(dnsServer.server)
                binding.serverEnabledSwitch.visibility = android.view.View.VISIBLE
                binding.serverEnabledSwitch.isChecked = dnsServer.enabled
                builder.setTitle(R.string.edit_server).setView(view)
                    .setPositiveButton(
                        R.string.menu_save
                    ) { _, _ ->
                        listener.onUpdateDialogPositiveClick(
                            dnsServer.id,
                            binding.editTextServerAddr.text.toString().trim(),
                            binding.editTextServerHint.text.toString().trim(),
                            binding.serverEnabledSwitch.isChecked
                        )
                    }
                    .setNegativeButton(
                        R.string.cancel
                    ) { _, _ ->
                        dialog?.cancel()
                    }
                    .setNeutralButton(
                        R.string.delete
                    ) { _, _ ->
                        listener.onDeleteItemClicked(dnsServer.id)
                    }
            } else {
                builder.setTitle(R.string.add_server)
                    .setView(view)
                    // Add action buttons
                    .setPositiveButton(
                        R.string.menu_add
                    ) { _, _ ->
                        listener.onAddDialogPositiveClick(
                            binding.editTextServerHint.text.toString().trim(),
                            binding.editTextServerAddr.text.toString().trim()
                        )
                    }
                    .setNegativeButton(
                        R.string.cancel
                    ) { _, _ ->
                        dialog?.cancel()
                    }
            }
            builder.create()
        } ?: throw IllegalStateException("Activity cannot be null")
    }

    private var isTextWatcherAdded = false

    override fun onStart() {
        super.onStart()
        val button = ((dialog) as AlertDialog).getButton(DialogInterface.BUTTON_POSITIVE)
        // onStart runs again after returning from the background, only set up once
        if (isTextWatcherAdded) return
        isTextWatcherAdded = true
        // Also covers text restored after a configuration change, which the watcher doesn't see
        button.isEnabled = isValidServer(binding.editTextServerAddr.text.toString().trim())
        binding.editTextServerAddr.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                val server = binding.editTextServerAddr.text.toString().trim()
                if (TextUtils.isEmpty(server) || !isValidServer(server)) {
                    button.isEnabled = false
                } else {
                    binding.editTextServerAddr.error = null
                    button.isEnabled = true
                }
            }
        })
    }

    private fun isValidServer(str: String): Boolean = ServerValidation.isValidHostname(str)

    companion object {
        private const val ARG_ID = "id"
        private const val ARG_SERVER = "server"
        private const val ARG_LABEL = "label"
        private const val ARG_ENABLED = "enabled"

        /**
         * Creates the dialog for adding a server, or editing [dnsServer] if it is not null
         * @param dnsServer The server to edit
         */
        fun newInstance(dnsServer: DnsServer?) = AddServerDialogFragment().apply {
            arguments = Bundle().apply {
                if (dnsServer != null) {
                    putInt(ARG_ID, dnsServer.id)
                    putString(ARG_SERVER, dnsServer.server)
                    putString(ARG_LABEL, dnsServer.label)
                    putBoolean(ARG_ENABLED, dnsServer.enabled)
                }
            }
        }
    }
}
