package com.example.nfcdemo

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NfcAdapter
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.accessibility.selecttospeak.KeepAliveService

class MainActivity : AppCompatActivity() {

    private var nfcAdapter: NfcAdapter? = null
    private var pendingIntent: PendingIntent? = null

    private lateinit var tvName: TextView
    private lateinit var tvPhone: TextView
    private lateinit var tvStatus: TextView
    private lateinit var btnIconWechat: ImageButton
    private lateinit var btnIconDial: ImageButton

    private var currentName: String = ""
    private var currentPhone: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

        tvName = findViewById(R.id.tv_name)
        tvPhone = findViewById(R.id.tv_phone)
        tvStatus = findViewById(R.id.tv_status)
        btnIconWechat = findViewById(R.id.btn_icon_wechat)
        btnIconDial = findViewById(R.id.btn_icon_dial)

        btnIconWechat.setOnClickListener { onWeChatCallClicked() }
        btnIconDial.setOnClickListener { onDialClicked() }

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        when {
            nfcAdapter == null -> {
                tvStatus.setText(R.string.nfc_not_supported)
            }
            nfcAdapter?.isEnabled != true -> tvStatus.setText(R.string.nfc_disabled)
            else -> tvStatus.setText(R.string.ready_to_scan)
        }

        prepareForegroundDispatch()
        handleIntent(intent)

        // 启动保活服务
        tryStartKeepAliveService()
    }

    private fun getActionAfterRead(): String {
        val prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(SettingsActivity.KEY_ACTION_AFTER_READ, SettingsActivity.ACTION_WECHAT_VIDEO)
            ?: SettingsActivity.ACTION_WECHAT_VIDEO
    }

    private fun onWeChatCallClicked() {
        if (currentName.isEmpty()) {
            Toast.makeText(this, "请先扫描 NFC 名片", Toast.LENGTH_SHORT).show()
            return
        }
        if (!ActionHelper.isAccessibilityServiceEnabled(this)) {
            AlertDialog.Builder(this)
                .setTitle("需要开启无障碍服务")
                .setMessage(R.string.accessibility_not_enabled)
                .setPositiveButton("去开启") { _, _ ->
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }
        ActionHelper.startWeChatCall(this, currentName)
    }

    companion object {
        private const val REQUEST_CALL_PHONE = 100
    }

    private fun onDialClicked() {
        if (currentPhone.isEmpty()) {
            Toast.makeText(this, "无手机号可拨打", Toast.LENGTH_SHORT).show()
            return
        }
        if (checkSelfPermission(android.Manifest.permission.CALL_PHONE) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            ActionHelper.makePhoneCall(this, currentPhone)
        } else {
            requestPermissions(arrayOf(android.Manifest.permission.CALL_PHONE), REQUEST_CALL_PHONE)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CALL_PHONE && grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            ActionHelper.makePhoneCall(this, currentPhone)
        } else {
            ActionHelper.openDialPage(this, currentPhone)
        }
    }

    private fun tryStartKeepAliveService() {
        if (!KeepAliveService.isRunning.value) {
            KeepAliveService.start(this)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_write -> {
                startActivity(Intent(this, WriteActivity::class.java))
                true
            }
            R.id.action_test -> {
                startActivity(Intent(this, TestActivity::class.java))
                true
            }
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onResume() {
        super.onResume()
        App.onActivityResumed()
        nfcAdapter?.takeIf { it.isEnabled }?.enableForegroundDispatch(this, pendingIntent, buildIntentFilters(), buildTechLists())
        tryStartKeepAliveService()
    }

    override fun onPause() {
        super.onPause()
        App.onActivityPaused()
        nfcAdapter?.disableForegroundDispatch(this)
    }

    private fun prepareForegroundDispatch() {
        val intent = Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else 0
        pendingIntent = PendingIntent.getActivity(this, 0, intent, flags)
    }

    private fun buildIntentFilters(): Array<IntentFilter> {
        val ndef = IntentFilter(NfcAdapter.ACTION_NDEF_DISCOVERED).apply {
            try {
                addDataType(NFC_MIME_TYPE)
            } catch (e: IntentFilter.MalformedMimeTypeException) {
                throw RuntimeException("Bad MIME type", e)
            }
        }
        return arrayOf(ndef, IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED), IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED))
    }

    private fun buildTechLists(): Array<Array<String>> = arrayOf(
        arrayOf(android.nfc.tech.Ndef::class.java.name),
        arrayOf(android.nfc.tech.NdefFormatable::class.java.name)
    )

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val action = intent.action ?: return
        if (action !in listOf(
                NfcAdapter.ACTION_NDEF_DISCOVERED,
                NfcAdapter.ACTION_TAG_DISCOVERED,
                NfcAdapter.ACTION_TECH_DISCOVERED
            )
        ) return

        @Suppress("DEPRECATION")
        val rawMsgs = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
        if (rawMsgs != null && rawMsgs.isNotEmpty()) {
            val msg = rawMsgs[0] as android.nfc.NdefMessage
            val data = parseNdefMessage(msg)
            showCard(data)
        } else {
            tvStatus.setText(R.string.tag_no_data)
            Toast.makeText(this, R.string.tag_no_data, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showCard(data: CardData) {
        currentName = data.name
        currentPhone = data.phone
        tvName.text = getString(R.string.label_name_value, data.name.takeIf { it.isNotEmpty() } ?: "-")
        tvPhone.text = getString(R.string.label_phone_value, data.phone.takeIf { it.isNotEmpty() } ?: "-")
        tvStatus.setText(R.string.read_success)
        tvStatus.visibility = TextView.VISIBLE

        val hasName = data.name.isNotEmpty()
        btnIconWechat.isEnabled = hasName
        btnIconDial.isEnabled = data.phone.isNotEmpty()

        // 根据设置自动触发动作
        val action = getActionAfterRead()
        if (hasName) {
            when (action) {
                SettingsActivity.ACTION_WECHAT_VIDEO -> onWeChatCallClicked()
                SettingsActivity.ACTION_DIAL_NUMBER -> onDialClicked()
                // ACTION_NO_ACTION 不自动触发
            }
        }
    }

}
