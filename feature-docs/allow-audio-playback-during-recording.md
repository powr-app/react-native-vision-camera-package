# Allow Audio Playback During Camera Recording

## Context

By default, when recording video in iOS with `react-native-vision-camera`, the system interrupts any background audio playback such as music. This happens because the audio session is configured to prioritize recording. This feature will allow applications to record video with the camera while maintaining background audio playback such as music, podcasts, or other audio sources.

This enhancement is particularly useful for applications that:

- Allow users to record videos with background music
- Need to maintain audio playback from other applications (like Spotify or Apple Music) during recording
- Want to provide a better user experience without interrupting the user's audio experience

## Implementation Strategy

### 1. Add a New Prop to Camera Component

Add a new boolean prop to the Camera component called `allowDeviceAudioPlayback` that defaults to `false` to maintain backward compatibility.

### 2. Update Native Interface

Pass this prop through to the native module to be accessible in the iOS implementation.

### 3. Modify iOS Audio Session Configuration

Update the iOS code to configure the audio session appropriately based on the prop value.

## Detailed Implementation Steps

### Step 1: Add the Prop to Camera Component

Update the `CameraProps` interface in `types/CameraProps.ts` to include the new prop:

```typescript
export interface CameraProps extends ViewProps {
  // ... existing props

  /**
   * Whether to allow other audio to continue playing while recording.
   * When set to `true`, background audio playback (like music) will continue during recording.
   * When set to `false` (default), starting a recording will pause any other audio playing on the device.
   *
   * @default false
   * @platform ios
   */
  allowDeviceAudioPlayback?: boolean
}
```

### Step 2: Pass the Prop to Native Module

In `Camera.tsx`, update the component to pass the new prop to the native component:

```typescript
// Inside the render method of Camera component
return (
  <NativeCameraView
    {...props}
    cameraId={device.id}
    ref={this.ref}
    allowDeviceAudioPlayback={props.allowDeviceAudioPlayback ?? false}
    // ... other props
  />
)
```

### Step 3: Update the Native Module Interface

Update the `NativeCameraViewProps` interface in `NativeCameraView.tsx` to include the new prop:

```typescript
export interface NativeCameraViewProps extends ViewProps {
  // ... existing props

  /**
   * Whether to allow other audio to continue playing while recording.
   */
  allowDeviceAudioPlayback?: boolean
}
```

### Step 4: Add Property to Objective-C Interface

In the iOS Objective-C interface for the CameraView component, add the new property:

```objective-c
// In CameraViewManager.m
RCT_EXPORT_VIEW_PROPERTY(allowDeviceAudioPlayback, BOOL)
```

### Step 5: Update Swift Implementation

In `CameraView.swift`, add a property to store the value:

```swift
@objc var allowDeviceAudioPlayback: NSNumber = false
```

### Step 6: Modify Audio Session Configuration

In `CameraView+RecordVideo.swift`, update the audio session configuration when starting recording:

```swift
func startRecording(options: NSDictionary, callback jsCallback: @escaping RCTResponseSenderBlock) {
  // Type-safety
  let callback = Callback(jsCallback)

  do {
    let options = try RecordVideoOptions(fromJSValue: options,
                                         bitRateOverride: videoBitRateOverride?.doubleValue,
                                         bitRateMultiplier: videoBitRateMultiplier?.doubleValue)

    // Configure the audio session based on allowDeviceAudioPlayback
    if allowDeviceAudioPlayback.boolValue {
      try AVAudioSession.sharedInstance().setCategory(.playAndRecord,
                                                     options: [.allowBluetooth, .defaultToSpeaker, .mixWithOthers])
    } else {
      // Default behavior - interrupts other audio
      try AVAudioSession.sharedInstance().setCategory(.record,
                                                     options: [.allowBluetooth])
    }

    // Start Recording with success and error callbacks
    cameraSession.startRecording(
      options: options,
      onVideoRecorded: { video in
        callback.resolve(video.toJSValue())
      },
      onError: { error in
        callback.reject(error: error)
      }
    )
  } catch {
    // Some error occured while initializing VideoSettings
    if let error = error as? CameraError {
      callback.reject(error: error)
    } else {
      callback.reject(error: .capture(.unknown(message: error.localizedDescription)), cause: error as NSError)
    }
  }
}
```

### Step 7: Clean Up Audio Session (Optional)

When recording stops, consider resetting the audio session if needed:

```swift
func stopRecording(promise: Promise) {
  cameraSession.stopRecording(promise: promise)

  // Optionally reset audio session if needed
  // try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
}
```

## Testing the Implementation

To test this feature:

1. Start playing music on the device
2. Open your app and initialize the camera with `allowDeviceAudioPlayback={true}`
3. Start recording
4. Verify that the music continues to play during recording
5. Stop recording and ensure everything returns to normal state

## Known Limitations

- This implementation is specific to iOS. Android handles audio sessions differently and typically allows multiple audio sources by default.
- Using `.mixWithOthers` might affect audio quality in some cases, as the system has to mix multiple audio streams.
- This approach doesn't provide control over the relative volumes of the recorded audio vs. background audio.

## Usage Example

Here's how to use the `allowDeviceAudioPlayback` prop to enable background audio playback during recording:

```tsx
import { Camera, useCameraDevice } from 'react-native-vision-camera'
import { StyleSheet, View } from 'react-native'

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

With this configuration, when you start recording video, any background audio playing (such as music from Spotify or Apple Music) will continue to play and be mixed with the recorded audio from the device's microphone.

If you set `allowDeviceAudioPlayback={false}` or omit the prop (it defaults to `false`), background audio will be paused when recording starts, which is the default behavior.

## Testing

To verify this feature is working:

1. Start playing music on your iOS device using any audio app
2. Open your app that uses VisionCamera with `allowDeviceAudioPlayback={true}`
3. Start recording a video
4. Confirm that the music continues to play while recording
5. Stop the recording and verify that the recorded video contains both the background music and audio captured from the microphone
