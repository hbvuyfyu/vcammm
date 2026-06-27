package com.vcam.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.vcam.R
import com.vcam.databinding.ActivityMainBinding
import com.vcam.service.VCamService
import com.vcam.utils.LicenseChecker
import com.vcam.viewmodel.MainViewModel
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    private val pickMedia = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { viewModel.setMediaUri(it, this) }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        lifecycleScope.launch {
            viewModel.initRoot()
            if (!allGranted) showSnack(getString(R.string.permissions_required))
        }
    }

    private val overlayPermLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupObservers()
        setupClickListeners()
        requestPermissions()
    }

    override fun onResume() {
        super.onResume()
        val savedCode = LicenseChecker.getSavedCode(this)
        if (savedCode == null) {
            logoutToCodeScreen()
            return
        }
        lifecycleScope.launch {
            val result = LicenseChecker.verifyCode(savedCode)
            if (result == LicenseChecker.VerifyResult.INVALID ||
                result == LicenseChecker.VerifyResult.SERVER_EMPTY) {
                LicenseChecker.clearCode(this@MainActivity)
                logoutToCodeScreen()
            }
        }
    }

    private fun logoutToCodeScreen() {
        startActivity(Intent(this, CodeActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }

    private fun setupObservers() {
        viewModel.mediaUri.observe(this) { uri ->
            if (uri != null) {
                binding.tvMediaSelected.text = getString(R.string.media_selected)
                binding.ivMediaPreview.setImageURI(uri)
                binding.cardMedia.visibility = View.VISIBLE
                binding.btnStartStop.isEnabled = true
            } else {
                binding.cardMedia.visibility = View.GONE
                binding.btnStartStop.isEnabled = false
            }
        }

        viewModel.isServiceRunning.observe(this) { running ->
            binding.btnStartStop.text = if (running) getString(R.string.stop_vcam)
                                         else getString(R.string.start_vcam)
            val color = if (running) R.color.color_stop else R.color.color_start
            binding.btnStartStop.backgroundTintList =
                androidx.core.content.res.ResourcesCompat.getColorStateList(resources, color, theme)
            binding.btnStartStop.setIconResource(if (running) R.drawable.ic_stop else R.drawable.ic_play)
        }

        viewModel.rootStatus.observe(this) { ok ->
            binding.tvRootStatus.text = if (ok) getString(R.string.root_granted)
                                         else getString(R.string.root_denied)
            binding.tvRootStatus.setTextColor(
                ContextCompat.getColor(this, if (ok) R.color.color_root_ok else R.color.color_root_fail)
            )
        }

        viewModel.errorMessage.observe(this) { msg ->
            if (!msg.isNullOrBlank()) { showSnack(msg); viewModel.clearError() }
        }
    }

    private fun setupClickListeners() {
        binding.btnPickImage?.setOnClickListener { pickMedia.launch("image/*") }
        binding.btnPickVideo?.setOnClickListener { pickMedia.launch("video/*") }
        binding.btnClearMedia?.setOnClickListener { viewModel.clearMedia() }

        binding.btnStartStop.setOnClickListener {
            if (viewModel.isServiceRunning.value == true) stopVCamService()
            else handleStart()
        }
    }

    private fun handleStart() {
        val mediaUri = viewModel.mediaUri.value ?: run {
            showSnack(getString(R.string.select_media_first)); return
        }
        checkOverlayThenStart(mediaUri)
    }

    private fun checkOverlayThenStart(mediaUri: Uri) {
        if (!Settings.canDrawOverlays(this)) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.overlay_permission_title)
                .setMessage(R.string.overlay_permission_msg)
                .setPositiveButton(R.string.grant) { _, _ ->
                    overlayPermLauncher.launch(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName"))
                    )
                    doStartService(mediaUri)
                }
                .setNegativeButton(R.string.skip) { _, _ -> doStartService(mediaUri) }
                .show()
        } else {
            doStartService(mediaUri)
        }
    }

    private fun doStartService(mediaUri: Uri) {
        val intent = Intent(this, VCamService::class.java).apply {
            action = VCamService.ACTION_START
            putExtra(VCamService.EXTRA_MEDIA_URI, mediaUri.toString())
            putExtra(VCamService.EXTRA_IS_VIDEO, viewModel.isVideo.value == true)
            // No EXTRA_TARGET_PACKAGE — always system-wide global injection
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
        viewModel.setServiceRunning(true)
        showSnack(getString(R.string.injection_active))
    }

    private fun stopVCamService() {
        startService(Intent(this, VCamService::class.java).apply { action = VCamService.ACTION_STOP })
        viewModel.setServiceRunning(false)
    }

    private fun requestPermissions() {
        val permissions = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(android.Manifest.permission.READ_MEDIA_IMAGES)
                add(android.Manifest.permission.READ_MEDIA_VIDEO)
                add(android.Manifest.permission.POST_NOTIFICATIONS)
            } else {
                add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            add(android.Manifest.permission.CAMERA)
        }
        val toRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (toRequest.isNotEmpty()) permissionLauncher.launch(toRequest.toTypedArray())
        else lifecycleScope.launch { viewModel.initRoot() }
    }

    private fun showSnack(msg: String) =
        Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
}
