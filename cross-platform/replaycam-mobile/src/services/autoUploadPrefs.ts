import * as FileSystem from 'expo-file-system';
import type { RecordingPreference } from '../types/replay';

const PREFS_FILE = `${FileSystem.documentDirectory ?? ''}replaycam-prefs.json`;

type Prefs = {
  autoUploadEnabled: boolean;
  driveLinkedEmail: string | null;
  driveAccessToken: string | null;
  driveAccessTokenExpiryMs: number | null;
  replayDurationSec: number;
  recordingFps: 30 | 60;
  recordingQuality: RecordingPreference['quality'];
};

const defaultPrefs: Prefs = {
  autoUploadEnabled: false,
  driveLinkedEmail: null,
  driveAccessToken: null,
  driveAccessTokenExpiryMs: null,
  replayDurationSec: 20,
  recordingFps: 30,
  recordingQuality: '1080'
};

async function readPrefs(): Promise<Prefs> {
  if (!FileSystem.documentDirectory) return defaultPrefs;
  const info = await FileSystem.getInfoAsync(PREFS_FILE);
  if (!info.exists) return defaultPrefs;
  try {
    const raw = await FileSystem.readAsStringAsync(PREFS_FILE);
    return { ...defaultPrefs, ...JSON.parse(raw) };
  } catch {
    return defaultPrefs;
  }
}

async function writePrefs(prefs: Prefs): Promise<void> {
  if (!FileSystem.documentDirectory) return;
  await FileSystem.writeAsStringAsync(PREFS_FILE, JSON.stringify(prefs));
}

export async function getAutoUploadEnabled(): Promise<boolean> {
  return (await readPrefs()).autoUploadEnabled;
}

export async function setAutoUploadEnabled(value: boolean): Promise<void> {
  const prefs = await readPrefs();
  await writePrefs({ ...prefs, autoUploadEnabled: value });
}

export async function getLinkedDriveEmail(): Promise<string | null> {
  return (await readPrefs()).driveLinkedEmail;
}

export async function getDriveAccessToken(): Promise<string | null> {
  return (await readPrefs()).driveAccessToken;
}

export async function getDriveAccessTokenExpiryMs(): Promise<number | null> {
  return (await readPrefs()).driveAccessTokenExpiryMs;
}

export async function setDriveSession(params: {
  email: string | null;
  accessToken: string | null;
  expiryMs: number | null;
}): Promise<void> {
  const prefs = await readPrefs();
  await writePrefs({
    ...prefs,
    driveLinkedEmail: params.email,
    driveAccessToken: params.accessToken,
    driveAccessTokenExpiryMs: params.expiryMs
  });
}

export async function clearDriveSession(): Promise<void> {
  const prefs = await readPrefs();
  await writePrefs({
    ...prefs,
    driveLinkedEmail: null,
    driveAccessToken: null,
    driveAccessTokenExpiryMs: null
  });
}

export async function getReplayDurationSec(): Promise<number> {
  return (await readPrefs()).replayDurationSec;
}

export async function setReplayDurationSec(value: number): Promise<void> {
  const prefs = await readPrefs();
  await writePrefs({ ...prefs, replayDurationSec: value });
}

export async function getRecordingPreference(): Promise<RecordingPreference> {
  const prefs = await readPrefs();
  return { fps: prefs.recordingFps, quality: prefs.recordingQuality };
}

export async function setRecordingPreference(value: RecordingPreference): Promise<void> {
  const prefs = await readPrefs();
  await writePrefs({ ...prefs, recordingFps: value.fps, recordingQuality: value.quality });
}
