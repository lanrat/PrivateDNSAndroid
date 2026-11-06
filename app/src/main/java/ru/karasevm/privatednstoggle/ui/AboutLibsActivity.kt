package ru.karasevm.privatednstoggle.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mikepenz.aboutlibraries.Libs
import com.mikepenz.aboutlibraries.entity.Library
import com.mikepenz.aboutlibraries.util.withJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.karasevm.privatednstoggle.R
import ru.karasevm.privatednstoggle.databinding.ActivityLicensesBinding
import ru.karasevm.privatednstoggle.databinding.ItemLicenseBinding

/**
 * Lists the open-source libraries in the app with their licenses, from the list the
 * AboutLibraries Gradle plugin generates at build time. Tapping one shows its license text.
 */
class AboutLibsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLicensesBinding
    private var licenseDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityLicensesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.topAppBar.setNavigationOnClickListener { finish() }
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.updatePadding(left = bars.left, right = bars.right)
            binding.topAppBarLayout.updatePadding(top = bars.top)
            binding.recyclerView.updatePadding(bottom = bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        lifecycleScope.launch {
            val libraries = withContext(Dispatchers.IO) {
                Libs.Builder().withJson(this@AboutLibsActivity, R.raw.aboutlibraries).build()
                    .libraries.sortedBy { it.name.lowercase() }
            }
            binding.recyclerView.adapter = LibraryAdapter(libraries, ::showLicense)
        }
    }

    override fun onDestroy() {
        licenseDialog?.dismiss()
        licenseDialog = null
        super.onDestroy()
    }

    private fun showLicense(library: Library) {
        val text = library.licenses.joinToString("\n\n") { license ->
            license.licenseContent?.takeIf { it.isNotBlank() }
                ?: listOfNotNull(license.name, license.url).joinToString("\n")
        }
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(library.name)
            .setMessage(text)
            .setPositiveButton(R.string.ok, null)
        library.website?.let { url ->
            builder.setNeutralButton(R.string.library_website) { _, _ -> openUrl(url) }
        }
        licenseDialog = builder.show()
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        } catch (_: ActivityNotFoundException) {
            // No browser; nothing to do
        }
    }

    private class LibraryAdapter(
        private val libraries: List<Library>,
        private val onClick: (Library) -> Unit,
    ) : RecyclerView.Adapter<LibraryAdapter.Holder>() {

        class Holder(val binding: ItemLicenseBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemLicenseBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = libraries.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val library = libraries[position]
            val licenses = library.licenses.joinToString { it.name }
            holder.binding.libraryName.text = library.name
            holder.binding.libraryDetails.text = library.artifactVersion
                ?.let { holder.itemView.context.getString(R.string.library_details, it, licenses) }
                ?: licenses
            holder.itemView.setOnClickListener { onClick(library) }
        }
    }
}
