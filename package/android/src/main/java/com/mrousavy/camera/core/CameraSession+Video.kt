package com.mrousavy.camera.core

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.video.ExperimentalPersistentRecording
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.VideoRecordEvent
import com.mrousavy.camera.core.extensions.getCameraError
import com.mrousavy.camera.core.types.RecordVideoOptions
import com.mrousavy.camera.core.types.Video

@OptIn(ExperimentalPersistentRecording::class)
@SuppressLint("MissingPermission", "RestrictedApi")
fun CameraSession.startRecording(
  enableAudio: Boolean,
  allowDeviceAudioPlayback: Boolean = false,  // Default to false for backward compatibility
  options: RecordVideoOptions,
  callback: (video: Video) -> Unit,
  onError: (error: CameraError) -> Unit
) {
  if (camera == null) throw CameraNotReadyError()
  if (recording != null) throw RecordingInProgressError()
  val videoOutput = videoOutput ?: throw VideoNotEnabledError()

  // Create output video file
  val outputOptions = FileOutputOptions.Builder(options.file.file).also { outputOptions ->
    metadataProvider.location?.let { location ->
      Log.i(CameraSession.TAG, "Setting Video Location to ${location.latitude}, ${location.longitude}...")
      outputOptions.setLocation(location)
    }
  }.build()

  // TODO: Move this to JS so users can prepare recordings earlier
  // Prepare recording
  var pendingRecording = videoOutput.output.prepareRecording(context, outputOptions)
  
  if (enableAudio) {
    checkMicrophonePermission()
    
    // Handle audio focus based on allowDeviceAudioPlayback
    if (allowDeviceAudioPlayback) {
      // Configure audio to allow background playback
      configureAudioForConcurrentPlayback()
    } else {
      // Use default behavior that may interrupt other audio
      configureDefaultAudio()
    }
    
    pendingRecording = pendingRecording.withAudioEnabled()
  }
  
  pendingRecording = pendingRecording.asPersistentRecording()

  isRecordingCanceled = false
  recording = pendingRecording.start(CameraQueues.cameraExecutor) { event ->
    when (event) {
      is VideoRecordEvent.Start -> Log.i(CameraSession.TAG, "Recording started!")

      is VideoRecordEvent.Resume -> Log.i(CameraSession.TAG, "Recording resumed!")

      is VideoRecordEvent.Pause -> Log.i(CameraSession.TAG, "Recording paused!")

      is VideoRecordEvent.Status -> Log.i(CameraSession.TAG, "Status update! Recorded ${event.recordingStats.numBytesRecorded} bytes.")

      is VideoRecordEvent.Finalize -> {
        if (isRecordingCanceled) {
          Log.i(CameraSession.TAG, "Recording was canceled, deleting file..")
          onError(RecordingCanceledError())
          try {
            options.file.file.delete()
          } catch (e: Throwable) {
            this.callback.onError(FileIOError(e))
          }
          return@start
        }

        Log.i(CameraSession.TAG, "Recording stopped!")
        val error = event.getCameraError()
        if (error != null) {
          if (error.wasVideoRecorded) {
            Log.e(CameraSession.TAG, "Video Recorder encountered an error, but the video was recorded anyways.", error)
          } else {
            Log.e(CameraSession.TAG, "Video Recorder encountered a fatal error!", error)
            onError(error)
            return@start
          }
        }

        // Prepare output result
        val durationMs = event.recordingStats.recordedDurationNanos / 1_000_000
        Log.i(CameraSession.TAG, "Successfully completed video recording! Captured ${durationMs.toDouble() / 1_000.0} seconds.")
        val path = event.outputResults.outputUri.path ?: throw UnknownRecorderError(false, null)
        val size = videoOutput.attachedSurfaceResolution ?: Size(0, 0)
        val video = Video(path, durationMs, size)
        callback(video)
      }
    }
  }
}

/**
 * Configures audio to allow other audio sources to continue playing during recording
 */
private fun CameraSession.configureAudioForConcurrentPlayback() {
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
    val audioAttributes = AudioAttributes.Builder()
      .setUsage(AudioAttributes.USAGE_MEDIA)
      .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
      .build()

    val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
      .setAudioAttributes(audioAttributes)
      .setAcceptsDelayedFocusGain(true)
      .setOnAudioFocusChangeListener { }
      .build()

    // Request audio focus without fully interrupting other audio
    audioManager.requestAudioFocus(focusRequest)
  } else {
    // For older Android versions
    @Suppress("DEPRECATION")
    audioManager.requestAudioFocus(
      null,
      AudioManager.STREAM_MUSIC,
      AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
    )
  }
}

/**
 * Configures audio with default behavior that may interrupt other audio sources
 */
private fun CameraSession.configureDefaultAudio() {
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
    val audioAttributes = AudioAttributes.Builder()
      .setUsage(AudioAttributes.USAGE_MEDIA)
      .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
      .build()

    val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
      .setAudioAttributes(audioAttributes)
      .setAcceptsDelayedFocusGain(true)
      .setOnAudioFocusChangeListener { }
      .build()

    // Request exclusive audio focus (may interrupt other audio)
    audioManager.requestAudioFocus(focusRequest)
  } else {
    // For older Android versions
    @Suppress("DEPRECATION")
    audioManager.requestAudioFocus(
      null,
      AudioManager.STREAM_MUSIC,
      AudioManager.AUDIOFOCUS_GAIN
    )
  }
}

/**
 * Abandons audio focus when recording stops
 */
private fun CameraSession.abandonAudioFocus() {
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
    // For Android 8.0+ (API level 26+)
    val audioAttributes = AudioAttributes.Builder()
      .setUsage(AudioAttributes.USAGE_MEDIA)
      .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
      .build()

    val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
      .setAudioAttributes(audioAttributes)
      .build()

    audioManager.abandonAudioFocusRequest(focusRequest)
  } else {
    // For older Android versions
    @Suppress("DEPRECATION")
    audioManager.abandonAudioFocus(null)
  }
}

fun CameraSession.stopRecording() {
  val recording = recording ?: throw NoRecordingInProgressError()

  recording.stop()
  this.recording = null
  
  // Abandon audio focus when recording stops
  abandonAudioFocus()
}

fun CameraSession.cancelRecording() {
  isRecordingCanceled = true
  stopRecording()
}

fun CameraSession.pauseRecording() {
  val recording = recording ?: throw NoRecordingInProgressError()
  recording.pause()
  
  // Temporarily abandon audio focus when recording is paused
  abandonAudioFocus()
}

fun CameraSession.resumeRecording() {
  val recording = recording ?: throw NoRecordingInProgressError()
  recording.resume()
  
  // Re-request audio focus when recording resumes
  // Since we don't know the original setting, we use the less intrusive option
  configureAudioForConcurrentPlayback()
}
