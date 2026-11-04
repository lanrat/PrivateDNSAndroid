package ru.karasevm.privatednstoggle.ui

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.mikepenz.aboutlibraries.ui.compose.android.produceLibraries
import com.mikepenz.aboutlibraries.ui.compose.m3.LibrariesContainer
import ru.karasevm.privatednstoggle.R

class AboutLibsActivity : AppCompatActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                val libraries by produceLibraries(R.raw.aboutlibraries)
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text(stringResource(R.string.open_source_licenses)) },
                            navigationIcon = {
                                IconButton(onClick = { finish() }) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.arrow_back_24px),
                                        contentDescription = "back"
                                    )
                                }
                            }
                        )
                    },
                    content = { paddingValues ->
                        LibrariesContainer(
                            libraries = libraries,
                            contentPadding = paddingValues
                        )
                    }
                )
            }
        }
    }
}