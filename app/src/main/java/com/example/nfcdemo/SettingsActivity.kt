package com.example.nfcdemo

import android.os.Bundle
import android.widget.RadioGroup
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    companion object {
        const val PREFS_NAME = "nfc_prefs"
        const val KEY_ACTION_AFTER_READ = "action_after_read"

        const val ACTION_WECHAT_VIDEO = "wechat_video"
        const val ACTION_DIAL_NUMBER = "dial_number"
        const val ACTION_NO_ACTION = "no_action"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val rgAction = findViewById<RadioGroup>(R.id.rg_action_after_read)
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val currentAction = prefs.getString(KEY_ACTION_AFTER_READ, ACTION_WECHAT_VIDEO) ?: ACTION_WECHAT_VIDEO

        when (currentAction) {
            ACTION_WECHAT_VIDEO -> rgAction.check(R.id.rb_wechat_video)
            ACTION_DIAL_NUMBER -> rgAction.check(R.id.rb_dial_number)
            ACTION_NO_ACTION -> rgAction.check(R.id.rb_no_action)
        }

        rgAction.setOnCheckedChangeListener { _, checkedId ->
            val value = when (checkedId) {
                R.id.rb_wechat_video -> ACTION_WECHAT_VIDEO
                R.id.rb_dial_number -> ACTION_DIAL_NUMBER
                R.id.rb_no_action -> ACTION_NO_ACTION
                else -> ACTION_WECHAT_VIDEO
            }
            prefs.edit().putString(KEY_ACTION_AFTER_READ, value).apply()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
