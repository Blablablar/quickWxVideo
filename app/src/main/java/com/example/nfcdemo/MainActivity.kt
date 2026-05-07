package com.example.nfcdemo

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NfcAdapter
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private var nfcAdapter: NfcAdapter? = null
    private var pendingIntent: PendingIntent? = null
    private var intentFilters: Array<IntentFilter>? = null
    private var techLists: Array<Array<String>>? = null

    private lateinit var tvName: TextView
    private lateinit var tvPhone: TextView
    private lateinit var tvStatus: TextView
    private lateinit var btnWechatCall: Button
    private lateinit var etTestName: EditText

    private var currentName: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvName = findViewById(R.id.tv_name)
        tvPhone = findViewById(R.id.tv_phone)
        tvStatus = findViewById(R.id.tv_status)
        val btnWrite: Button = findViewById(R.id.btn_go_write)
        val btnClear: Button = findViewById(R.id.btn_clear)
        btnWechatCall = findViewById(R.id.btn_wechat_call)
        etTestName = findViewById(R.id.et_test_name)
        val btnTestJw: Button = findViewById(R.id.btn_test_jw)

        btnWrite.setOnClickListener {
            startActivity(Intent(this, WriteActivity::class.java))
        }
        btnClear.setOnClickListener { resetUi() }
        btnWechatCall.setOnClickListener { onWeChatCallClicked() }
        btnWechatCall.isEnabled = false
        btnTestJw.setOnClickListener {
            val testName = etTestName.text.toString().trim()
            if (testName.isEmpty()) {
                Toast.makeText(this, "请输入测试姓名", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            currentName = testName
            tvName.text = getString(R.string.label_name_value, testName)
            tvPhone.text = getString(R.string.label_phone_value, "-")
            btnWechatCall.isEnabled = true
            onWeChatCallClicked()
        }

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        when {
            nfcAdapter == null -> {
                tvStatus.setText(R.string.nfc_not_supported)
                btnWrite.isEnabled = false
            }
            nfcAdapter?.isEnabled != true -> tvStatus.setText(R.string.nfc_disabled)
            else -> tvStatus.setText(R.string.ready_to_scan)
        }

        prepareForegroundDispatch()
        handleIntent(intent)
    }

    private fun onWeChatCallClicked() {
        if (currentName.isEmpty()) {
            Toast.makeText(this, "请先扫描 NFC 名片", Toast.LENGTH_SHORT).show()
            return
        }
        if (!isAccessibilityServiceEnabled()) {
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
        Toast.makeText(this, R.string.wechat_call_started, Toast.LENGTH_SHORT).show()
        val service = com.google.android.accessibility.selecttospeak.SelectToSpeakService.instance
        if (service != null) {
            service.startCall(currentName)
        } else {
            // 服务尚未连接，保存目标名字，等服务启动后自动执行
            getSharedPreferences("nfc_prefs", Context.MODE_PRIVATE)
                .edit()
                .putString("target_name", currentName)
                .putBoolean("should_start", true)
                .apply()
            // 尝试打开微信，等服务连上后自动继续
            val wechatIntent = packageManager.getLaunchIntentForPackage("com.tencent.mm")
            if (wechatIntent != null) {
                wechatIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                startActivity(wechatIntent)
            } else {
                Toast.makeText(this, "未安装微信", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val am = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        if (!am.isEnabled) return false
        val list = am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        return list.any { it.resolveInfo.serviceInfo.packageName == packageName }
    }

    private fun prepareForegroundDispatch() {
        val intent = Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else 0
        pendingIntent = PendingIntent.getActivity(this, 0, intent, flags)

        val ndef = IntentFilter(NfcAdapter.ACTION_NDEF_DISCOVERED).apply {
            try {
                addDataType(NFC_MIME_TYPE)
            } catch (e: IntentFilter.MalformedMimeTypeException) {
                throw RuntimeException("Bad MIME type", e)
            }
        }
        val tag = IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED)
        val tech = IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED)
        intentFilters = arrayOf(ndef, tag, tech)
        techLists = arrayOf(
            arrayOf(android.nfc.tech.Ndef::class.java.name),
            arrayOf(android.nfc.tech.NdefFormatable::class.java.name)
        )
    }

    override fun onResume() {
        super.onResume()
        nfcAdapter?.takeIf { it.isEnabled }?.enableForegroundDispatch(this, pendingIntent, intentFilters, techLists)
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
    }

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
        tvName.text = getString(R.string.label_name_value, data.name.takeIf { it.isNotEmpty() } ?: "-")
        tvPhone.text = getString(R.string.label_phone_value, data.phone.takeIf { it.isNotEmpty() } ?: "-")
        tvStatus.setText(R.string.read_success)
        tvStatus.visibility = TextView.VISIBLE
        btnWechatCall.isEnabled = data.name.isNotEmpty()
        if (data.name.isNotEmpty()) {
            onWeChatCallClicked()
        }
    }

    private fun resetUi() {
        currentName = ""
        etTestName.text.clear()
        tvName.text = getString(R.string.label_name_value, "-")
        tvPhone.text = getString(R.string.label_phone_value, "-")
        tvStatus.setText(R.string.ready_to_scan)
        btnWechatCall.isEnabled = false
    }
}
