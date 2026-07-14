package com.truecapture.app

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import org.json.JSONObject

// The settings screen. Holds the frame rate, the front flash colour, the
// accessibility options, and buttons to back settings up to a file.
class SettingsActivity : AppCompatActivity() {

    // Save every setting to a file the user picks.
    private val exportFile =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            uri?.let { writeSettings(it) }
        }

    // Load settings back from a file the user picks.
    private val importFile =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { readSettings(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportFragmentManager.beginTransaction()
            .replace(android.R.id.content, SettingsFragment())
            .commit()
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    fun startExport() {
        exportFile.launch("elendheim-capture-settings.json")
    }

    fun startImport() {
        importFile.launch(arrayOf("application/json"))
    }

    private fun writeSettings(uri: Uri) {
        try {
            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            val json = JSONObject()
            for ((key, value) in prefs.all) {
                json.put(key, value)
            }
            contentResolver.openOutputStream(uri)?.use { out ->
                out.write(json.toString(2).toByteArray())
            }
            Toast.makeText(this, "Settings exported", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Could not export settings", Toast.LENGTH_SHORT).show()
        }
    }

    private fun readSettings(uri: Uri) {
        try {
            val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: return
            val json = JSONObject(text)
            val editor = PreferenceManager.getDefaultSharedPreferences(this).edit()
            for (key in json.keys()) {
                when (val value = json.get(key)) {
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putString(key, value.toString())
                    else -> editor.putString(key, value.toString())
                }
            }
            editor.apply()
            Toast.makeText(this, "Settings imported", Toast.LENGTH_SHORT).show()
            // Rebuild the screen so the new values show.
            recreate()
        } catch (e: Exception) {
            Toast.makeText(this, "Could not import settings", Toast.LENGTH_SHORT).show()
        }
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.root_preferences, rootKey)

            findPreference<Preference>("export_settings")?.setOnPreferenceClickListener {
                (activity as? SettingsActivity)?.startExport()
                true
            }
            findPreference<Preference>("import_settings")?.setOnPreferenceClickListener {
                (activity as? SettingsActivity)?.startImport()
                true
            }
        }
    }
}
