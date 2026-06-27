package com.vcam.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.vcam.utils.RootManager
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _mediaUri = MutableLiveData<Uri?>()
    val mediaUri: LiveData<Uri?> = _mediaUri

    private val _isVideo = MutableLiveData(false)
    val isVideo: LiveData<Boolean> = _isVideo

    private val _isServiceRunning = MutableLiveData(false)
    val isServiceRunning: LiveData<Boolean> = _isServiceRunning

    private val _rootStatus = MutableLiveData(false)
    val rootStatus: LiveData<Boolean> = _rootStatus

    private val _errorMessage = MutableLiveData<String?>()
    val errorMessage: LiveData<String?> = _errorMessage

    fun initRoot() {
        viewModelScope.launch {
            val hasRoot = RootManager.requestRoot()
            _rootStatus.value = hasRoot
        }
    }

    fun setMediaUri(uri: Uri, context: Context) {
        _mediaUri.value = uri
        val mimeType = context.contentResolver.getType(uri)
        _isVideo.value = mimeType?.startsWith("video/") == true
    }

    fun clearMedia() {
        _mediaUri.value = null
        _isVideo.value = false
    }

    fun setServiceRunning(running: Boolean) { _isServiceRunning.value = running }
    fun clearError() { _errorMessage.value = null }
}
