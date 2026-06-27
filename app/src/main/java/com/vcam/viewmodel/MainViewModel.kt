package com.vcam.viewmodel

  import android.app.Application
  import android.content.Context
  import android.net.Uri
  import androidx.lifecycle.AndroidViewModel
  import androidx.lifecycle.LiveData
  import androidx.lifecycle.MutableLiveData
  import androidx.lifecycle.viewModelScope
  import com.vcam.R
  import com.vcam.model.AppInfo
  import com.vcam.utils.AppLoader
  import com.vcam.utils.RootManager
  import kotlinx.coroutines.Dispatchers
  import kotlinx.coroutines.launch
  import kotlinx.coroutines.withContext

  class MainViewModel(application: Application) : AndroidViewModel(application) {

      private val _filteredApps = MutableLiveData<List<AppInfo>>()
      val filteredApps: LiveData<List<AppInfo>> = _filteredApps

      private val _selectedApp = MutableLiveData<AppInfo?>()
      val selectedApp: LiveData<AppInfo?> = _selectedApp

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

      private val _isLoading = MutableLiveData(false)
      val isLoading: LiveData<Boolean> = _isLoading

      private var allApps: List<AppInfo> = emptyList()
      private var currentFilter: String = ""
      private var chipFilter: Int? = null

      fun initRoot() {
          viewModelScope.launch {
              val hasRoot = RootManager.requestRoot()
              _rootStatus.value = hasRoot
          }
      }

      fun loadInstalledApps(context: Context) {
          viewModelScope.launch {
              _isLoading.value = true
              try {
                  val apps = withContext(Dispatchers.IO) {
                      AppLoader.getInstalledApps(context)
                  }
                  allApps = apps
                  applyFilters()
              } catch (e: Exception) {
                  _errorMessage.value = "Failed to load apps: ${e.message}"
              } finally {
                  _isLoading.value = false
              }
          }
      }

      fun selectTargetApp(app: AppInfo) { _selectedApp.value = app }
      fun clearSelectedApp() { _selectedApp.value = null }

      fun setMediaUri(uri: Uri, context: Context) {
          _mediaUri.value = uri
          val mimeType = context.contentResolver.getType(uri)
          _isVideo.value = mimeType?.startsWith("video/") == true
      }

      fun clearMedia() { _mediaUri.value = null; _isVideo.value = false }
      fun setServiceRunning(running: Boolean) { _isServiceRunning.value = running }

      fun filterApps(query: String) { currentFilter = query; applyFilters() }
      fun setAppFilter(chipId: Int?) { chipFilter = chipId; applyFilters() }
      fun clearError() { _errorMessage.value = null }

      private fun applyFilters() {
          var filtered = allApps

          if (currentFilter.isNotBlank()) {
              filtered = filtered.filter {
                  it.appName.contains(currentFilter, ignoreCase = true) ||
                  it.packageName.contains(currentFilter, ignoreCase = true)
              }
          }

          when (chipFilter) {
              R.id.chip_camera -> filtered = filtered.filter { it.useCamera }
              R.id.chip_user   -> filtered = filtered.filter { !it.isSystem }
          }

          _filteredApps.value = filtered
      }
  }
  