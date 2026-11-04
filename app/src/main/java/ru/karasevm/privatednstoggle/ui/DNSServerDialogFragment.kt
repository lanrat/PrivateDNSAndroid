package ru.karasevm.privatednstoggle.ui

import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
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
                    servers.add(0, DnsServer(-1, resources.getString(R.string.dns_auto)))
                    servers.add(0, DnsServer(-2, resources.getString(R.string.dns_off)))
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
        adapter.onItemClick = { id ->
            val previous = DnsSetting(
                PrivateDNSUtils.getPrivateMode(contentResolver).orEmpty(),
                PrivateDNSUtils.getPrivateProvider(contentResolver)
            )
            val server = servers.find { server -> server.id == id }
            val next = when (id) {
                OFF_ID -> DnsSetting(PrivateDNSUtils.DNS_MODE_OFF, null)
                AUTO_ID -> DnsSetting(PrivateDNSUtils.DNS_MODE_AUTO, null)
                else -> server?.let { DnsSetting(PrivateDNSUtils.DNS_MODE_PRIVATE, it.server) }
            }
            if (next != null) {
                PrivateDNSUtils.applyDnsSetting(contentResolver, next)
                val message = when (id) {
                    OFF_ID -> getString(R.string.set_to_off_toast)
                    AUTO_ID -> getString(R.string.set_to_auto_toast)
                    else -> getString(
                        R.string.set_to_provider_toast,
                        server?.label?.ifEmpty { null } ?: next.provider
                    )
                }
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                AutoRevert.onDnsChanged(requireContext(), previous, next, mayStart = true)
            }
            // The tile observes the system settings and refreshes itself
            dialog?.dismiss()
        }

    }

    override fun onDestroy() {
        super.onDestroy()
        // On a configuration change the activity restores this dialog instead
        if (activity?.isChangingConfigurations != true) activity?.finish()
    }

    companion object {
        const val TAG = "DNSServerDialogFragment"
        private const val AUTO_ID = -1
        private const val OFF_ID = -2
    }
}