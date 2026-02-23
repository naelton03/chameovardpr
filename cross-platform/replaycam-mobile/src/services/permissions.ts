import { Camera } from 'expo-camera';
import * as MediaLibrary from 'expo-media-library';
import type { PermissionSnapshot } from '../types/replay';

export async function requestAllPermissions(): Promise<PermissionSnapshot> {
  const cameraResult = await Camera.requestCameraPermissionsAsync();
  const micResult = await Camera.requestMicrophonePermissionsAsync();
  const mediaResult = await MediaLibrary.requestPermissionsAsync();

  return {
    camera: cameraResult.granted,
    microphone: micResult.granted,
    mediaLibrary: mediaResult.granted
  };
}
