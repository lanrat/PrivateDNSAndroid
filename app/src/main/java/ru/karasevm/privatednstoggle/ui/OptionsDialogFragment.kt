package ru.karasevm.privatednstoggle.ui

import android.app.Dialog
import android.os.Bundle
import android.view.View
import androidx.core.view.children
import androidx.fragment.app.DialogFragment
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import ru.karasevm.privatednstoggle.R
import ru.karasevm.privatednstoggle.app
import ru.karasevm.privatednstoggle.databinding.DialogOptionsBinding
import ru.karasevm.privatednstoggle.util.AutoRevert
import ru.karasevm.privatednstoggle.util.PreferenceHelper
import ru.karasevm.privatednstoggle.util.PreferenceHelper.allowExternalControl
import ru.karasevm.privatednstoggle.util.PreferenceHelper.autoMode
import ru.karasevm.privatednstoggle.util.PreferenceHelper.autoRevertEnabled
import ru.karasevm.privatednstoggle.util.PreferenceHelper.autoRevertMinutes
import ru.karasevm.privatednstoggle.util.PreferenceHelper.requireUnlock
import ru.karasevm.privatednstoggle.util.PrivateDNSUtils

class OptionsDialogFragment : DialogFragment() {
    private var _binding: DialogOptionsBinding? = null
    private val binding get() = _binding!!
    private val sharedPreferences by lazy { PreferenceHelper.defaultPreference(requireContext()) }

    override fun onCreateDialog(
        savedInstanceState: Bundle?
    ): Dialog {
        return activity?.let {
            val builder = MaterialAlertDialogBuilder(it)
            val inflater = requireActivity().layoutInflater
            _binding = DialogOptionsBinding.inflate(inflater)
            setUpDelayChips()

            val view = binding.root
            builder.setTitle(R.string.options)
                .setView(view)
                .setPositiveButton(R.string.ok, null)
            builder.create()

        } ?: throw IllegalStateException("Activity cannot be null")
    }

    /**
     * Adds a chip for each preset delay, checks the saved one and saves the choice right away
     */
    private fun setUpDelayChips() {
        val group = binding.autoRevertDelayGroup
        // Not the fragment's layoutInflater, which isn't available inside onCreateDialog
        val inflater = requireActivity().layoutInflater
        val selected = sharedPreferences.autoRevertMinutes
        for (minutes in AutoRevert.PRESET_MINUTES) {
            val chip = inflater.inflate(R.layout.item_duration_chip, group, false) as Chip
            chip.id = View.generateViewId()
            chip.text = AutoRevert.formatDuration(minutes)
            chip.tag = minutes
            group.addView(chip)
            if (minutes == selected) group.check(chip.id)
        }
        group.setOnCheckedStateChangeListener { chipGroup, checkedIds ->
            val minutes = checkedIds.firstOrNull()?.let { chipGroup.findViewById<Chip>(it).tag as Int }
            if (minutes != null) sharedPreferences.autoRevertMinutes = minutes
        }
    }

    private fun setDelayChipsEnabled(enabled: Boolean) {
        binding.autoRevertDelayLabel.isEnabled = enabled
        binding.autoRevertDelayGroup.children.forEach { it.isEnabled = enabled }
    }

    override fun onStart() {
        super.onStart()
        val autoModeOption = sharedPreferences.autoMode
        when (autoModeOption) {
            PrivateDNSUtils.AUTO_MODE_OPTION_OFF -> binding.autoOptionRadioGroup.check(R.id.autoOptionOff)
            PrivateDNSUtils.AUTO_MODE_OPTION_AUTO -> binding.autoOptionRadioGroup.check(R.id.autoOptionAuto)
            PrivateDNSUtils.AUTO_MODE_OPTION_OFF_AUTO -> binding.autoOptionRadioGroup.check(R.id.autoOptionOffAuto)
            PrivateDNSUtils.AUTO_MODE_OPTION_PRIVATE -> binding.autoOptionRadioGroup.check(R.id.autoOptionPrivate)
        }
        binding.autoOptionRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.autoOptionOff -> sharedPreferences.autoMode = PrivateDNSUtils.AUTO_MODE_OPTION_OFF
                R.id.autoOptionAuto -> sharedPreferences.autoMode = PrivateDNSUtils.AUTO_MODE_OPTION_AUTO
                R.id.autoOptionOffAuto -> sharedPreferences.autoMode =
                    PrivateDNSUtils.AUTO_MODE_OPTION_OFF_AUTO

                R.id.autoOptionPrivate -> sharedPreferences.autoMode =
                    PrivateDNSUtils.AUTO_MODE_OPTION_PRIVATE
            }
        }

        val requireUnlock = sharedPreferences.requireUnlock
        binding.requireUnlockSwitch.isChecked = requireUnlock
        binding.requireUnlockSwitch.setOnCheckedChangeListener { _, isChecked ->
            sharedPreferences.requireUnlock = isChecked
        }

        binding.allowExternalControlSwitch.isChecked = sharedPreferences.allowExternalControl
        binding.allowExternalControlSwitch.setOnCheckedChangeListener { _, isChecked ->
            sharedPreferences.allowExternalControl = isChecked
        }

        binding.autoRevertSwitch.isChecked = sharedPreferences.autoRevertEnabled
        setDelayChipsEnabled(sharedPreferences.autoRevertEnabled)
        binding.autoRevertSwitch.setOnCheckedChangeListener { _, isChecked ->
            sharedPreferences.autoRevertEnabled = isChecked
            setDelayChipsEnabled(isChecked)
            if (!isChecked) requireContext().app.dnsController.cancelSwitchBack()
        }
    }
}
