# Allow Audio Playback During Camera Recording - Android Implementation

## Context

By default, when recording video in Android with `react-native-vision-camera`, the camera session may interfere with background audio playback. This feature will allow applications to record video with the camera while maintaining background audio playback such as music, podcasts, or other audio sources.

This implementation complements the iOS implementation described in `allow-audio-playback-during-recording.md` and provides a consistent cross-platform experience for users.

## Android-Specific Considerations

In Android, audio handling differs from iOS:

1. Android typically uses the `AudioManager` to handle audio focus and routing
2. The CameraX library used by Vision Camera handles audio recording through `PendingRecording` objects
3. We need to configure audio focus appropriately to allow background playback to continue during recording

## Implementation Strategy

### 1. Use the Same Prop as iOS

We'll use the same `allowDeviceAudioPlayback` boolean prop that was implemented for iOS to maintain a consistent API.

### 2. Update Android Native Code

Modify the Android code to respect this prop by configuring the audio focus and mode appropriately.

### 3. Handle Audio Focus

Implement proper audio focus handling to allow or prevent background audio based on the prop's value.

## Detailed Implementation Steps

### Step 1: Update the Parameter in CameraViewManager

Ensure the `allowDeviceAudioPlayback` property is passed from React Native to Android:

```kotlin
// In CameraViewManager.kt
@ReactProp(name = "allowDeviceAudioPlayback")
fun setAllowDeviceAudioPlayback(view: CameraView, allowDeviceAudioPlayback: Boolean) {
  view.setAllowDeviceAudioPlayback(allowDeviceAudioPlayback)
}
```

### Step 2: Add the Property to CameraView

Add a property to store the value and a setter method:

```kotlin
// In CameraView.kt
private var allowDeviceAudioPlayback = false

fun setAllowDeviceAudioPlayback(allow: Boolean) {
  allowDeviceAudioPlayback = allow
}
```

### Step 3: Pass the Property to CameraSession

When starting a recording, pass the new property to the CameraSession:

```kotlin
// In CameraView.kt (in the startRecording method)
cameraSession.startRecording(
  enableAudio = enableAudio,
  allowDeviceAudioPlayback = allowDeviceAudioPlayback,
  options = options,
  callback = callback,
  onError = onError
)
```

### Step 4: Modify the CameraSession+Video.kt File

Update the `startRecording` function to accept and use the new parameter:

```kotlin
// In CameraSession+Video.kt
fun CameraSession.startRecording(
  enableAudio: Boolean,
  allowDeviceAudioPlayback: Boolean = false,  // Default to false for backward compatibility
  options: RecordVideoOptions,
  callback: (video: Video) -> Unit,
  onError: (error: CameraError) -> Unit
) {
  // Existing code...

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

  // Rest of existing code...
}
```

### Step 5: Implement Audio Focus Management

Add methods to handle different audio focus requirements:

```kotlin
// In CameraSession.kt
private fun configureAudioForConcurrentPlayback() {
  val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

  // For Android 8.0+ (API level 26+)
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
    val audioAttributes = AudioAttributes.Builder()
      .setUsage(AudioAttributes.USAGE_MEDIA)
      .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
      .build()

    val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
      .setAudioAttributes(audioAttributes)
      .setAcceptsDelayedFocusGain(true)
      .setOnAudioFocusChangeListener { focusChange ->
        // Handle focus changes if needed
      }
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

private fun configureDefaultAudio() {
  val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

  // For Android 8.0+ (API level 26+)
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
    val audioAttributes = AudioAttributes.Builder()
      .setUsage(AudioAttributes.USAGE_MEDIA)
      .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
      .build()

    val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
      .setAudioAttributes(audioAttributes)
      .setAcceptsDelayedFocusGain(true)
      .setOnAudioFocusChangeListener { focusChange ->
        // Handle focus changes if needed
      }
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
```

### Step 6: Clean Up Audio Focus When Recording Stops

Add cleanup code to the `stopRecording` method:

```kotlin
// In CameraSession+Video.kt
fun CameraSession.stopRecording() {
  val recording = recording ?: throw NoRecordingInProgressError()

  recording.stop()
  this.recording = null

  // Abandon audio focus when recording stops
  abandonAudioFocus()
}

private fun CameraSession.abandonAudioFocus() {
  val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
    // For Android 8.0+ (API level 26+)
    if (this::focusRequest.isInitialized) {
      audioManager.abandonAudioFocusRequest(focusRequest)
    }
  } else {
    // For older Android versions
    @Suppress("DEPRECATION")
    audioManager.abandonAudioFocus(null)
  }
}
```

### Step 7: Store Audio Focus Request for Later Abandonment

Add a property to store the audio focus request:

```kotlin
// In CameraSession.kt
@RequiresApi(Build.VERSION_CODES.O)
private lateinit var focusRequest: AudioFocusRequest
```

## Testing the Implementation

To test this feature:

1. Start playing music on the device using a music app
2. Open your app and initialize the camera with `allowDeviceAudioPlayback={true}`
3. Start recording
4. Verify that the music continues to play during recording
5. Stop recording and ensure the music continues playing
6. Try the same test with `allowDeviceAudioPlayback={false}` or not set, and verify that the music is paused during recording

## Known Limitations

- Different Android devices and versions may handle audio focus in slightly different ways
- Some Android OEMs might have custom implementations that could affect this behavior
- In some cases, the system may still reduce the volume of background audio (ducking) even when using `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`

## Usage Example

Using the `allowDeviceAudioPlayback` prop is identical on both iOS and Android, providing a consistent cross-platform experience. Here's how to use it:

```tsx
import { Camera, useCameraDevice } from 'react-native-vision-camera'
import { StyleSheet, View, Text } from 'react-native'

export default function CameraWithBackgroundAudio() {
  const device = useCameraDevice('back')

  if (device == null)
    return (
      <View>
        <Text>Camera not available</Text>
      </View>
    )

  return (
    <Camera
      style={StyleSheet.absoluteFill}
      device={device}
      isActive={true}
      video={true}
      audio={true}
      // Enable background audio playback during recording
      allowDeviceAudioPlayback={true}
    />
  )
}
```

With this configuration, when you start recording video, any background audio playing (such as music from Spotify or other music apps) will continue to play and be mixed with the recorded audio from the device's microphone.

If you set `allowDeviceAudioPlayback={false}` or omit the prop (it defaults to `false`), background audio will be paused when recording starts, which is the default behavior.

## Testing

To verify this feature is working on Android:

1. Start playing music on your Android device using any audio app
2. Open your app that uses VisionCamera with `allowDeviceAudioPlayback={true}`
3. Start recording a video
4. Confirm that the music continues to play while recording (possibly at a slightly reduced volume due to ducking)
5. Stop recording and ensure the music continues playing at its original volume
6. Try the same test with `allowDeviceAudioPlayback={false}` or not set, and verify that the music is paused during recording

## Additional Considerations

- For performance and audio quality reasons, users should be aware that concurrent audio playback may affect the quality of the recorded audio
- The implementation uses `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` which allows other audio to continue playing but at a reduced volume. This is the standard Android behavior for mixing audio sources
- The audio focus management is handled automatically, including the proper cleanup when recording stops, pauses, or resumes
