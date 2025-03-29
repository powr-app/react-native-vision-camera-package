//
//  CameraView+RecordVideo.swift
//  mrousavy
//
//  Created by Marc Rousavy on 16.12.20.
//  Copyright © 2020 mrousavy. All rights reserved.
//

import AVFoundation

// MARK: - CameraView + AVCaptureVideoDataOutputSampleBufferDelegate, AVCaptureAudioDataOutputSampleBufferDelegate

extension CameraView: AVCaptureVideoDataOutputSampleBufferDelegate, AVCaptureAudioDataOutputSampleBufferDelegate {
  func startRecording(options: NSDictionary, callback jsCallback: @escaping RCTResponseSenderBlock) {
    // Type-safety
    let callback = Callback(jsCallback)

    do {
      let options = try RecordVideoOptions(fromJSValue: options,
                                           bitRateOverride: videoBitRateOverride?.doubleValue,
                                           bitRateMultiplier: videoBitRateMultiplier?.doubleValue)

      // Configure the audio session based on allowDeviceAudioPlayback
      if audio {
        do {
          try AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
          if allowDeviceAudioPlayback {
            try AVAudioSession.sharedInstance().setCategory(.playAndRecord,
                                                           mode: .videoRecording,
                                                           options: [.mixWithOthers, .allowBluetooth, .defaultToSpeaker])
          } else {
            // Default behavior - interrupts other audio
            try AVAudioSession.sharedInstance().setCategory(.record,
                                                           mode: .videoRecording,
                                                           options: [.allowBluetooth])
          }
          try AVAudioSession.sharedInstance().setActive(true)
        } catch {
          callback.reject(error: .capture(.unknown(message: error.localizedDescription)), cause: error as NSError)
          return
        }
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

  func stopRecording(promise: Promise) {
    cameraSession.stopRecording(promise: promise)
    
    // Reset audio session if needed
    if audio {
      do {
        try AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
      } catch {
        // We don't want to reject the promise here since the recording was successful
        // Just log the error
        print("Error resetting audio session: \(error.localizedDescription)")
      }
    }
  }

  func cancelRecording(promise: Promise) {
    cameraSession.cancelRecording(promise: promise)
    
    // Reset audio session if needed
    if audio {
      do {
        try AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
      } catch {
        // We don't want to reject the promise here since the cancellation was successful
        // Just log the error
        print("Error resetting audio session: \(error.localizedDescription)")
      }
    }
  }

  func pauseRecording(promise: Promise) {
    cameraSession.pauseRecording(promise: promise)
  }

  func resumeRecording(promise: Promise) {
    cameraSession.resumeRecording(promise: promise)
  }
}
