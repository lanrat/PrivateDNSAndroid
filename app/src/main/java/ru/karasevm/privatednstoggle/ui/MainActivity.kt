package ru.karasevm.privatednstoggle.ui

import android.content.ClipData
import android.content.ClipDescription.MIMETYPE_TEXT_PLAIN
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ShareCompat
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.karasevm.privatednstoggle.R
import ru.karasevm.privatednstoggle.app
import ru.karasevm.privatednstoggle.data.DnsServerViewModel
import ru.karasevm.privatednstoggle.data.DnsServerViewModelFactory
import ru.karasevm.privatednstoggle.databinding.ActivityMainBinding
import ru.karasevm.privatednstoggle.model.DnsServer
import java.io.IOException
import kotlin.math.roundToInt

/**
 * The server list, with the menu for options, import/export and licenses.
 *
 * The work is done elsewhere: [DnsServerViewModel] (servers and backups), [PermissionFlow]
 * (getting WRITE_SECURE_SETTINGS) and [ServerReorderCallback] (drag and drop).
 */
class MainActivity : AppCompatActivity(), AddServerDialogFragment.NoticeDialogListener,
    DeleteServerDialogFragment.NoticeDialogListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: ServerListRecyclerAdapter
    private lateinit var clipboard: ClipboardManager
    private val dnsServerViewModel: DnsServerViewModel by viewModels { DnsServerViewModelFactory(app) }

    // Like the platform contracts, but only offering files that can be opened as a stream
    private val exportFileLauncher =
        registerForActivityResult(object : ActivityResultContracts.CreateDocument("text/plain") {
            override fun createIntent(context: Context, input: String) =
                super.createIntent(context, input).addCategory(Intent.CATEGORY_OPENABLE)
        }) { uri ->
            if (uri != null) exportToFile(uri)
        }

    private val importFileLauncher =
        registerForActivityResult(object : ActivityResultContracts.OpenDocument() {
            override fun createIntent(context: Context, input: Array<String>) =
                super.createIntent(context, input).addCategory(Intent.CATEGORY_OPENABLE)
        }) { uri ->
            if (uri != null) importFromFile(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PermissionFlow(this)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager

        setUpServerList()
        binding.floatingActionButton.setOnClickListener {
            AddServerDialogFragment.newInstance(null).show(supportFragmentManager, "add_server")
        }
        binding.topAppBar.setOnMenuItemClickListener { item -> onMenuItemClick(item.itemId) }
        handleWindowInsets()
    }

    override fun onResume() {
        super.onResume()
        // Run a switch-back whose alarm was delayed
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { app.dnsController.runDueSwitchBack() }
                ?.let { showSwitchedBackToast(it) }
        }
    }

    private fun setUpServerList() {
        adapter = ServerListRecyclerAdapter(true)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        dnsServerViewModel.allServers.observe(this) { servers ->
            adapter.submitList(servers)
            val emptyVisibility = if (servers.isEmpty()) View.VISIBLE else View.GONE
            binding.emptyView.visibility = emptyVisibility
            binding.emptyViewHint.visibility = emptyVisibility
        }
        adapter.onItemClick = { id ->
            // Synchronous, so the dialog can't be shown after the activity was stopped, and
            // a double tap doesn't open two
            val server = adapter.getItem(id)
            if (server != null && supportFragmentManager.findFragmentByTag(EDIT_SERVER_TAG) == null) {
                AddServerDialogFragment.newInstance(server).show(supportFragmentManager, EDIT_SERVER_TAG)
            }
        }

        // Positions match sortOrder
        val itemTouchHelper = ItemTouchHelper(ServerReorderCallback(adapter) { from, to, id ->
            dnsServerViewModel.move(from, to, id)
        })
        itemTouchHelper.attachToRecyclerView(binding.recyclerView)
        adapter.onDragStart = { viewHolder -> itemTouchHelper.startDrag(viewHolder) }
        // Moving without dragging, for accessibility services
        adapter.onMove = { from, to, id ->
            adapter.onItemMove(from, to)
            adapter.refreshMoveActions()
            dnsServerViewModel.move(from, to, id)
        }
    }

    private fun onMenuItemClick(itemId: Int): Boolean {
        when (itemId) {
            R.id.privacy_policy -> startActivity(
                Intent(Intent.ACTION_VIEW, "https://karasevm.github.io/PrivateDNSAndroid/privacy_policy".toUri())
            )

            R.id.export_settings_clipboard -> lifecycleScope.launch {
                clipboard.setPrimaryClip(ClipData.newPlainText("", dnsServerViewModel.exportJson()))
                // Android 13+ shows its own confirmation
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) toast(R.string.copy_success)
            }

            R.id.export_settings_share -> lifecycleScope.launch {
                ShareCompat.IntentBuilder(this@MainActivity)
                    .setText(dnsServerViewModel.exportJson())
                    .setType("text/plain")
                    .startChooser()
            }

            R.id.export_settings_file -> exportFileLauncher.launch("private-dns-export")
            R.id.import_settings_file -> importFileLauncher.launch(arrayOf("text/plain"))
            R.id.import_settings_clipboard ->
                clipboard.primaryClip?.getItemAt(0)?.text?.let { importJson(it.toString()) }

            R.id.options -> OptionsDialogFragment().show(supportFragmentManager, "options")
            R.id.open_source_licenses -> startActivity(Intent(this, AboutLibsActivity::class.java))
        }
        return true
    }

    private fun exportToFile(uri: Uri) = lifecycleScope.launch {
        runCatching {
            val json = dnsServerViewModel.exportJson()
            withContext(Dispatchers.IO) {
                val outputStream = contentResolver.openOutputStream(uri)
                    ?: throw IOException("Could not open $uri")
                outputStream.use { it.write(json.toByteArray()) }
            }
        }.onSuccess {
            toast(R.string.export_success)
        }.onFailure { exception ->
            Log.e(TAG, "Export failed", exception)
            toast(R.string.export_failure)
        }
    }

    private fun importFromFile(uri: Uri) = lifecycleScope.launch {
        // The file may be unreadable, e.g. an offline cloud file
        runCatching {
            withContext(Dispatchers.IO) {
                val inputStream = contentResolver.openInputStream(uri)
                    ?: throw IOException("Could not open $uri")
                inputStream.bufferedReader().use { it.readText() }
            }
        }.onSuccess { json ->
            importJson(json)
        }.onFailure { exception ->
            Log.e(TAG, "Reading $uri failed", exception)
            toast(R.string.import_failure)
        }
    }

    private fun importJson(json: String) {
        dnsServerViewModel.importJson(json) { success ->
            toast(if (success) R.string.import_success else R.string.import_failure)
        }
    }

    private fun handleWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val fabMargin = (16 * resources.displayMetrics.density).roundToInt()
            val fabClearance = (56 * resources.displayMetrics.density).roundToInt() + fabMargin * 2

            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.updatePadding(left = bars.left, right = bars.right)
            binding.topAppBarLayout.updatePadding(top = bars.top)
            binding.floatingActionButton.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = fabMargin + bars.bottom
            }
            binding.recyclerView.updatePadding(bottom = fabClearance + bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) {
            // Only offer pasting when the clipboard holds text
            binding.topAppBar.menu.findItem(R.id.import_settings_clipboard).isEnabled =
                clipboard.hasPrimaryClip() &&
                    clipboard.primaryClipDescription?.hasMimeType(MIMETYPE_TEXT_PLAIN) == true
        }
    }

    /**
     *  Show the dialog for deleting the server
     *  @param id The server id
     */
    override fun onDeleteItemClicked(id: Int) {
        DeleteServerDialogFragment.newInstance(id).show(supportFragmentManager, "delete_server")
    }

    /**
     *  Callback for adding the server
     *  @param label The label
     *  @param server The server
     */
    override fun onAddDialogPositiveClick(label: String?, server: String) {
        if (server.isEmpty()) {
            toast(R.string.server_length_error)
            return
        }
        dnsServerViewModel.insertIfAbsent(DnsServer(0, server, label.orEmpty())) {
            toast(R.string.server_exists_error)
        }
    }

    /**
     *  Callback for deleting the server
     *  @param id The server id
     */
    override fun onDeleteDialogPositiveClick(id: Int) {
        dnsServerViewModel.delete(id)
    }

    /**
     *  Callback for updating the server
     *  @param label New label
     *  @param server New server address
     *  @param id The server id
     */
    override fun onUpdateDialogPositiveClick(id: Int, server: String, label: String?, enabled: Boolean) {
        if (server.isEmpty()) {
            toast(R.string.server_length_error)
            return
        }
        dnsServerViewModel.updateIfUnique(id, server, label, enabled) {
            toast(R.string.server_exists_error)
        }
    }

    // The application context, so toasts from callbacks also work after the activity is gone
    private fun toast(@StringRes message: Int) =
        Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()

    private companion object {
        const val TAG = "MainActivity"
        const val EDIT_SERVER_TAG = "edit_server"
    }
}
