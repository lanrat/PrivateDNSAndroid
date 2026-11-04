package ru.karasevm.privatednstoggle.ui

import android.annotation.SuppressLint
import android.app.Dialog
import android.os.Bundle
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import ru.karasevm.privatednstoggle.R
import ru.karasevm.privatednstoggle.databinding.DialogOptionsBinding
import ru.karasevm.privatednstoggle.util.PreferenceHelper
import ru.karasevm.privatednstoggle.util.PreferenceHelper.allowExternalControl
import ru.karasevm.privatednstoggle.util.PreferenceHelper.autoMode
import ru.karasevm.privatednstoggle.util.PreferenceHelper.autoRevertEnabled
import ru.karasevm.privatednstoggle.util.PreferenceHelper.autoRevertMinutes
import ru.karasevm.privatednstoggle.util.PreferenceHelper.requireUnlock
import ru.karasevm.privatednstoggle.util.AutoRevert
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

            val view = binding.root
            builder.setTitle(R.string.options)
                .setView(view)
                .setPositiveButton(R.string.ok, null)
            builder.create()

        } ?: throw IllegalStateException("Activity cannot be null")
    }

    private var isMinutesFieldSetUp = false

    /**
     * Fills in the switch-back delay and saves it whenever it is valid
     */
    // The number field only takes ASCII digits, which is also what toIntOrNull parses
    @SuppressLint("SetTextI18n")
    private fun setUpMinutesField() {
        binding.autoRevertMinutes.setText(sharedPreferences.autoRevertMinutes.toString())
        binding.autoRevertMinutes.doAfterTextChanged { text ->
            val minutes = text?.toString()?.toIntOrNull()
            if (minutes != null && minutes in AutoRevert.MIN_MINUTES..AutoRevert.MAX_MINUTES) {
                binding.autoRevertMinutesLayout.error = null
                sharedPreferences.autoRevertMinutes = minutes
            } else {
                binding.autoRevertMinutesLayout.error = getString(
                    R.string.auto_revert_minutes_error,
                    AutoRevert.MIN_MINUTES,
                    AutoRevert.MAX_MINUTES
                )
            }
        }
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

        // onStart can run again (e.g. after returning to the app), only set these up once
        if (!isMinutesFieldSetUp) {
            isMinutesFieldSetUp = true
            setUpMinutesField()
        }
        binding.autoRevertSwitch.isChecked = sharedPreferences.autoRevertEnabled
        binding.autoRevertMinutesLayout.isEnabled = sharedPreferences.autoRevertEnabled
        binding.autoRevertSwitch.setOnCheckedChangeListener { _, isChecked ->
            sharedPreferences.autoRevertEnabled = isChecked
            binding.autoRevertMinutesLayout.isEnabled = isChecked
            if (!isChecked) AutoRevert.cancel(requireContext())
        }
    }
}