package ru.karasevm.privatednstoggle.ui

import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import ru.karasevm.privatednstoggle.PrivateDNSApp
import ru.karasevm.privatednstoggle.R
import ru.karasevm.privatednstoggle.data.DnsServerViewModel
import ru.karasevm.privatednstoggle.data.DnsServerViewModelFactory
import ru.karasevm.privatednstoggle.databinding.SheetDnsSelectorBinding
import ru.karasevm.privatednstoggle.model.DnsServer
import ru.karasevm.privatednstoggle.util.AutoRevert
import ru.karasevm.privatednstoggle.util.DnsSetting
import ru.karasevm.privatednstoggle.util.PrivateDNSUtils
import ru.karasevm.privatednstoggle.util.PrivateDNSUtils.checkForPermission

class DNSServerDialogFragment : DialogFragment() {

    private var _binding: SheetDnsSelectorBinding? = null
    private val binding get() = _binding!!

    private lateinit var linearLayoutManager: LinearLayoutManager
    private lateinit var adapter: ServerListRecyclerAdapter
    private var servers: MutableList<DnsServer> = mutableListOf()
    private val dnsServerViewModel: DnsServerViewModel by viewModels { DnsServerViewModelFactory((requireActivity().application as PrivateDNSApp).repository) }
    private val contentResolver by lazy { requireActivity().contentResolver }

    override fun onCreateDialog(
        savedInstanceState: Bundle?
    ): Dialog {
        return activity?.let {
            val startIntent = Intent(context, MainActivity::class.java)

            val builder = MaterialAlertDialogBuilder(it)
            val inflater = requireActivity().layoutInflater
            _binding = SheetDnsSelectorBinding.inflate(inflater)
            linearLayoutManager = LinearLayoutManager(context)
            binding.recyclerView.layoutManager = linearLayoutManager

            adapter = ServerListRecyclerAdapter(false)
            binding.recyclerView.adapter = adapter
            lifecycleScope.launch {
                dnsServerViewModel.getAll().collect { s ->
                    servers = s.toMutableList()
                    if (servers.isEmpty()) {
                        servers.add(DnsServer(0, "dns.google"))
                    }
                    servers.add(0, DnsServer(AUTO_ID, resources.getString(R.string.dns_auto)))
                    // "Off for…" needs a server to switch back to
                    if (AutoRevert.switchBackTarget(requireContext()) != null) {
                        servers.add(0, DnsServer(OFF_FOR_ID, resources.getString(R.string.dns_off_for)))
                    }
                    servers.add(0, DnsServer(OFF_ID, resources.getString(R.string.dns_off)))
                    adapter.submitList(servers)
                }
            }
            builder.setTitle(R.string.select_server)
                .setView(binding.root)
                .setPositiveButton(
                    R.string.done
                ) { _, _ ->
                    dialog?.dismiss()
                }
                .setNeutralButton(R.string.open_app) { _, _ -> context?.startActivity(startIntent) }
            builder.create()
        } ?: throw IllegalStateException("Activity cannot be null")
    }

    override fun onStart() {
        super.onStart()
        if (!checkForPermission(requireContext())) {
            Toast.makeText(
                context, R.string.permission_missing, Toast.LENGTH_SHORT
            ).show()
            dismiss()
            return
        }
        adapter.onItemClick = onItemClick@{ id ->
            if (id == OFF_FOR_ID) {
                showOffForPicker()
                return@onItemClick
            }
            val server = servers.find { server -> server.id == id }
            val next = when (id) {
                OFF_ID -> DnsSetting(PrivateDNSUtils.DNS_MODE_OFF, null)
                AUTO_ID -> DnsSetting(PrivateDNSUtils.DNS_MODE_AUTO, null)
                else -> server?.let { DnsSetting(PrivateDNSUtils.DNS_MODE_PRIVATE, it.server) }
            }
            if (next != null && applySafely { AutoRevert.applyUserChange(requireContext(), next, fromOtherApp = false) }) {
                val message = when (id) {
                    OFF_ID -> getString(R.string.set_to_off_toast)
                    AUTO_ID -> getString(R.string.set_to_auto_toast)
                    else -> getString(
                        R.string.set_to_provider_toast,
                        server?.label?.ifEmpty { null } ?: next.provider
                    )
                }
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
            // The tile observes the system settings and refreshes itself
            dialog?.dismiss()
        }

    }

    private var offForDialog: AlertDialog? = null

    /**
     * Runs [change], showing a toast instead of crashing if the permission was revoked since
     * the dialog opened.
     *
     * @return whether the change was made
     */
    private fun applySafely(change: () -> Unit): Boolean = try {
        change()
        true
    } catch (e: SecurityException) {
        Log.w(TAG, "could not change settings", e)
        Toast.makeText(context, R.string.permission_missing, Toast.LENGTH_SHORT).show()
        false
    }

    /**
     * Asks how long to turn Private DNS off for, then does it and closes the picker.
     */
    private fun showOffForPicker() {
        val presets = AutoRevert.PRESET_MINUTES
        offForDialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.off_for_title)
            .setItems(presets.map { AutoRevert.formatDuration(it) }.toTypedArray()) { _, which ->
                applySafely { AutoRevert.turnOffFor(requireContext(), presets[which]) }
                dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onDestroy() {
        // Not restored after a configuration change, the user can pick it again
        offForDialog?.dismiss()
        offForDialog = null
        super.onDestroy()
        // On a configuration change the activity restores this dialog instead
        if (activity?.isChangingConfigurations != true) activity?.finish()
    }

    companion object {
        const val TAG = "DNSServerDialogFragment"
        private const val AUTO_ID = -1
        private const val OFF_ID = -2
        private const val OFF_FOR_ID = -3
    }
}