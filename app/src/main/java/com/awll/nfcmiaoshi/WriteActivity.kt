package com.awll.nfcmiaoshi

import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class WriteActivity : AppCompatActivity() {

    private var nfcAdapter: NfcAdapter? = null
    private var pendingIntent: PendingIntent? = null

    private lateinit var etName: EditText
    private lateinit var etPhone: EditText
    private lateinit var tvHint: TextView
    private lateinit var btnArm: Button
    private lateinit var btnCancel: Button

    private var armed = false
    private var pendingMessage: NdefMessage? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_write)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        etName = findViewById(R.id.et_name)
        etPhone = findViewById(R.id.et_phone)
        tvHint = findViewById(R.id.tv_hint)
        btnArm = findViewById(R.id.btn_arm)
        btnCancel = findViewById(R.id.btn_cancel)

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        btnArm.setOnClickListener { armWrite() }
        btnCancel.setOnClickListener { cancelWrite() }

        prepareForegroundDispatch()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun prepareForegroundDispatch() {
        val intent = Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else 0
        pendingIntent = PendingIntent.getActivity(this, 0, intent, flags)
    }

    private fun buildIntentFilters(): Array<IntentFilter> = arrayOf(
        IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED),
        IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED),
        IntentFilter(NfcAdapter.ACTION_NDEF_DISCOVERED)
    )

    private fun buildTechLists(): Array<Array<String>> = arrayOf(
        arrayOf(android.nfc.tech.Ndef::class.java.name),
        arrayOf(android.nfc.tech.NdefFormatable::class.java.name)
    )

    private fun armWrite() {
        val name = etName.text.toString().trim()
        val phone = etPhone.text.toString().trim()

        if (name.isEmpty()) {
            Toast.makeText(this, R.string.input_name_required, Toast.LENGTH_SHORT).show()
            return
        }
        if (nfcAdapter == null) {
            Toast.makeText(this, R.string.nfc_not_supported, Toast.LENGTH_SHORT).show()
            return
        }
        if (nfcAdapter?.isEnabled != true) {
            Toast.makeText(this, R.string.nfc_disabled, Toast.LENGTH_SHORT).show()
            return
        }

        pendingMessage = buildNdefMessage(name, phone)
        armed = true
        tvHint.setText(R.string.hint_tap_tag)
        btnArm.isEnabled = false
        btnCancel.isEnabled = true
    }

    private fun cancelWrite() {
        armed = false
        pendingMessage = null
        tvHint.setText(R.string.hint_fill_then_arm)
        btnArm.isEnabled = true
        btnCancel.isEnabled = false
    }

    override fun onResume() {
        super.onResume()
        nfcAdapter?.takeIf { it.isEnabled }?.enableForegroundDispatch(this, pendingIntent, buildIntentFilters(), buildTechLists())
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (!armed || pendingMessage == null) return

        @Suppress("DEPRECATION")
        val tag = intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG)
        if (tag == null) {
            Toast.makeText(this, R.string.write_no_tag, Toast.LENGTH_SHORT).show()
            return
        }

        try {
            writeTag(tag, pendingMessage!!)
            Toast.makeText(this, R.string.write_success, Toast.LENGTH_LONG).show()
            etName.text.clear()
            etPhone.text.clear()
            cancelWrite()
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.write_failed_format, e.message), Toast.LENGTH_LONG).show()
        }
    }
}
