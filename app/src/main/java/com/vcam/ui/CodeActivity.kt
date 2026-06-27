package com.vcam.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.vcam.R
import com.vcam.utils.LicenseChecker
import kotlinx.coroutines.launch

class CodeActivity : AppCompatActivity() {

    private lateinit var etCode: EditText
    private lateinit var btnSubmit: Button
    private lateinit var tvStatus: TextView
    private lateinit var progressBar: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_code)

        etCode = findViewById(R.id.et_code)
        btnSubmit = findViewById(R.id.btn_submit)
        tvStatus = findViewById(R.id.tv_status)
        progressBar = findViewById(R.id.progress_bar)

        val savedCode = LicenseChecker.getSavedCode(this)
        if (savedCode != null) {
            setLoading(true)
            verifyAndProceed(savedCode, isAutoCheck = true)
        }

        btnSubmit.setOnClickListener {
            val code = etCode.text.toString().trim()
            if (code.isEmpty()) {
                showError(getString(R.string.enter_code_hint))
                return@setOnClickListener
            }
            verifyAndProceed(code)
        }
    }

    private fun verifyAndProceed(code: String, isAutoCheck: Boolean = false) {
        setLoading(true)
        lifecycleScope.launch {
            when (LicenseChecker.verifyCode(code)) {
                LicenseChecker.VerifyResult.VALID -> {
                    LicenseChecker.saveCode(this@CodeActivity, code)
                    goToMain()
                }
                LicenseChecker.VerifyResult.INVALID -> {
                    LicenseChecker.clearCode(this@CodeActivity)
                    setLoading(false)
                    showError(getString(R.string.code_invalid))
                }
                LicenseChecker.VerifyResult.SERVER_EMPTY -> {
                    LicenseChecker.clearCode(this@CodeActivity)
                    setLoading(false)
                    showError(getString(R.string.code_revoked))
                }
                LicenseChecker.VerifyResult.NETWORK_ERROR -> {
                    setLoading(false)
                    if (isAutoCheck && !code.isBlank()) {
                        // Offline fallback: allow if saved code exists and network is unavailable
                        goToMain()
                    } else {
                        showError(getString(R.string.network_error))
                    }
                }
            }
        }
    }

    private fun goToMain() {
        startActivity(Intent(this@CodeActivity, MainActivity::class.java))
        finish()
    }

    private fun setLoading(loading: Boolean) {
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        btnSubmit.isEnabled = !loading
        etCode.isEnabled = !loading
        if (loading) tvStatus.visibility = View.GONE
    }

    private fun showError(msg: String) {
        tvStatus.text = msg
        tvStatus.visibility = View.VISIBLE
    }
}
