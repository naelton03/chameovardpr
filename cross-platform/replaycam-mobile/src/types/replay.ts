export type LivePrivacy = 'public' | 'unlisted' | 'private';

export interface CameraOption {
  id: string;
  label: string;
  maxFps: number;
  maxResolution: string;
  maxZoom: number;
  isLogicalMultiCamera: boolean;
}

export interface LiveConfig {
  title: string;
  privacy: LivePrivacy;
}

export interface ReplayStatus {
  message: string;
  isError?: boolean;
}

export interface SegmentInfo {
  uri: string;
  startedAt: number;
  endedAt: number;
  durationSec: number;
}

export interface PermissionSnapshot {
  camera: boolean;
  microphone: boolean;
  mediaLibrary: boolean;
}

export interface SavedVideoInfo {
  fileName: string;
  localUri: string;
  album: string;
}
