import * as FileSystem from 'expo-file-system';

const PREFS_FILE = `${FileSystem.documentDirectory ?? ''}replaycam-prefs.json`;

type Prefs = {
  autoUploadEnabled: boolean;
  driveLinkedEmail: string | null;
  driveAccessToken: string | null;
  driveAccessTokenExpiryMs: number | null;
};

const defaultPrefs: Prefs = {
  autoUploadEnabled: false,
  driveLinkedEmail: null,
  driveAccessToken: null,
  driveAccessTokenExpiryMs: null
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
