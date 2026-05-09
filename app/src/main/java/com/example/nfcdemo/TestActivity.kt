package com.example.nfcdemo

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class TestActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_CALL_PHONE = 101
    }

    private var pendingPhone: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_test)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val etName = findViewById<EditText>(R.id.et_test_name)
        val etPhone = findViewById<EditText>(R.id.et_test_phone)
        val btnTestCall = findViewById<Button>(R.id.btn_test_call)
        val btnTestDial = findViewById<Button>(R.id.btn_test_dial)

        btnTestCall.setOnClickListener {
            val name = etName.text.toString().trim()
            if (name.isEmpty()) {
                Toast.makeText(this, "请输入测试姓名", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!ActionHelper.isAccessibilityServiceEnabled(this)) {
                AlertDialog.Builder(this)
                    .setTitle("需要开启无障碍服务")
                    .setMessage(R.string.accessibility_not_enabled)
                    .setPositiveButton("去开启") { _, _ ->
                        startActivity(android.content.Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                    .setNegativeButton("取消", null)
                    .show()
                return@setOnClickListener
            }
            ActionHelper.startWeChatCall(this, name)
        }

        btnTestDial.setOnClickListener {
            val phone = etPhone.text.toString().trim()
            if (phone.isEmpty()) {
                Toast.makeText(this, "请输入手机号", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            pendingPhone = phone
            if (checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
                ActionHelper.makePhoneCall(this, phone)
            } else {
                requestPermissions(arrayOf(Manifest.permission.CALL_PHONE), REQUEST_CALL_PHONE)
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CALL_PHONE && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            ActionHelper.makePhoneCall(this, pendingPhone)
        } else {
            ActionHelper.openDialPage(this, pendingPhone)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
