package com.vcam.ui

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.ImageView
import android.widget.TextView
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
import com.vcam.utils.MediaSlotManager
import com.vcam.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    // Track which slot is being picked
    private var pendingSlot = 1

    private val pickMedia = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@registerForActivityResult
        val slot    = pendingSlot
        val isVideo = slot == 5
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                MediaSlotManager.setSlot(this@MainActivity, slot, uri, isVideo)
            }
            refreshSlotUI(slot)
            // Enable Start if slot 1 is set
            binding.btnStartStop.isEnabled = MediaSlotManager.isSlotSet(this@MainActivity, 1)
        }
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
        setupSlotPickers()
        setupStartStop()
        requestPermissions()
        // Refresh all slot UIs on create
        (1..5).forEach { refreshSlotUI(it) }
        binding.btnStartStop.isEnabled = MediaSlotManager.isSlotSet(this, 1)
    }

    override fun onResume() {
        super.onResume()
        val savedCode = LicenseChecker.getSavedCode(this)
        if (savedCode == null) { logoutToCodeScreen(); return }
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

    // ── Observers ─────────────────────────────────────────────────────────

    private fun setupObservers() {
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

    // ── Slot pickers ──────────────────────────────────────────────────────

    private fun setupSlotPickers() {
        // Image slots 1-4
        listOf(
            R.id.btn_pick_slot_1 to 1,
            R.id.btn_pick_slot_2 to 2,
            R.id.btn_pick_slot_3 to 3,
            R.id.btn_pick_slot_4 to 4,
        ).forEach { (btnId, slot) ->
            binding.root.findViewById<View>(btnId)?.setOnClickListener {
                pendingSlot = slot
                pickMedia.launch("image/*")
            }
        }

        // Video slot 5
        binding.root.findViewById<View>(R.id.btn_pick_slot_5)?.setOnClickListener {
            pendingSlot = 5
            pickMedia.launch("video/*")
        }
    }

    // ── Refresh slot thumbnail + status label ─────────────────────────────

    private fun refreshSlotUI(slot: Int) {
        val ivId = when (slot) {
            1 -> R.id.iv_slot_1; 2 -> R.id.iv_slot_2; 3 -> R.id.iv_slot_3
            4 -> R.id.iv_slot_4; else -> R.id.iv_slot_5
        }
        val tvId = when (slot) {
            1 -> R.id.tv_slot_1_status; 2 -> R.id.tv_slot_2_status; 3 -> R.id.tv_slot_3_status
            4 -> R.id.tv_slot_4_status; else -> R.id.tv_slot_5_status
        }
        val iv = binding.root.findViewById<ImageView>(ivId) ?: return
        val tv = binding.root.findViewById<TextView>(tvId) ?: return

        if (MediaSlotManager.isSlotSet(this, slot)) {
            tv.text    = getString(R.string.slot_ready)
            tv.setTextColor(0xFF22C55E.toInt())
            iv.visibility = View.VISIBLE
            lifecycleScope.launch {
                val bmp: Bitmap? = withContext(Dispatchers.IO) {
                    MediaSlotManager.getThumbnail(this@MainActivity, slot)
                }
                if (bmp != null) iv.setImageBitmap(bmp)
            }
        } else {
            tv.text = getString(R.string.slot_empty)
            tv.setTextColor(0xFF555555.toInt())
            iv.visibility = View.GONE
        }
    }

    // ── Start / Stop ──────────────────────────────────────────────────────

    private fun setupStartStop() {
        binding.btnStartStop.setOnClickListener {
            if (viewModel.isServiceRunning.value == true) stopVCamService()
            else handleStart()
        }
    }

    private fun handleStart() {
        if (!MediaSlotManager.isSlotSet(this, 1)) {
            showSnack(getString(R.string.select_media_first)); return
        }
        checkOverlayThenStart()
    }

    private fun checkOverlayThenStart() {
        if (!Settings.canDrawOverlays(this)) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.overlay_permission_title)
                .setMessage(R.string.overlay_permission_msg)
                .setPositiveButton(R.string.grant) { _, _ ->
                    overlayPermLauncher.launch(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName"))
                    )
                    doStartService()
                }
                .setNegativeButton(R.string.skip) { _, _ -> doStartService() }
                .show()
        } else {
            doStartService()
        }
    }

    private fun doStartService() {
        val slot1Path = MediaSlotManager.getSlotPath(this, 1) ?: return
        val intent = Intent(this, VCamService::class.java).apply {
            action = VCamService.ACTION_START
            putExtra(VCamService.EXTRA_MEDIA_PATH, slot1Path)
            putExtra(VCamService.EXTRA_IS_VIDEO, false)
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

    // ── Permissions ───────────────────────────────────────────────────────

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
