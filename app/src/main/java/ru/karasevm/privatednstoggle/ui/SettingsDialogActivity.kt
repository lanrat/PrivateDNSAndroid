package ru.karasevm.privatednstoggle.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class SettingsDialogActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // After recreation the fragment manager restores the existing dialog
        if (savedInstanceState != null) return
        val newFragment = DNSServerDialogFragment()
        newFragment.show(supportFragmentManager, DNSServerDialogFragment.TAG)
    }
}