import * as AuthSession from 'expo-auth-session';
import * as FileSystem from 'expo-file-system';
import { appendDiagnosticLog } from './logger';
import {
  clearDriveSession,
  getDriveAccessToken,
  getDriveAccessTokenExpiryMs,
  getLinkedDriveEmail,
  setDriveSession
} from './autoUploadPrefs';
import { DRIVE_SCOPE, GOOGLE_WEB_CLIENT_ID } from '../config/google';

const discovery = {
  authorizationEndpoint: 'https://accounts.google.com/o/oauth2/v2/auth',
  tokenEndpoint: 'https://oauth2.googleapis.com/token',
  revocationEndpoint: 'https://oauth2.googleapis.com/revoke'
};

function tokenExpired(expiryMs: number | null): boolean {
  if (!expiryMs) return true;
  return Date.now() > expiryMs - 60_000;
}

async function requireToken(): Promise<string> {
  const token = await getDriveAccessToken();
  const expiryMs = await getDriveAccessTokenExpiryMs();
  if (!token || tokenExpired(expiryMs)) {
    throw new Error('Sessão Google expirada. Vincule novamente a conta Drive.');
  }
  return token;
}

export async function linkedDriveEmail(): Promise<string | null> {
  return getLinkedDriveEmail();
}

export async function isDriveLinked(): Promise<boolean> {
  const token = await getDriveAccessToken();
  const expiryMs = await getDriveAccessTokenExpiryMs();
  return Boolean(token && !tokenExpired(expiryMs));
}

export async function linkDriveAccount(): Promise<string> {
  const redirectUri = AuthSession.makeRedirectUri({ scheme: 'replaycam' });

  const request = new AuthSession.AuthRequest({
    clientId: GOOGLE_WEB_CLIENT_ID,
    scopes: [DRIVE_SCOPE, 'openid', 'email', 'profile'],
    responseType: AuthSession.ResponseType.Token,
    redirectUri,
    extraParams: {
      prompt: 'consent'
    }
  });

  await request.makeAuthUrlAsync(discovery);
  const result = await request.promptAsync(discovery);

  if (result.type !== 'success' || !('access_token' in result.params)) {
    throw new Error('Falha ao autenticar com Google Drive.');
  }

  const accessToken = result.params.access_token;
  const expiresIn = Number(result.params.expires_in ?? '3600');
  const expiryMs = Date.now() + expiresIn * 1000;

  const userInfoResponse = await fetch('https://openidconnect.googleapis.com/v1/userinfo', {
    headers: { Authorization: `Bearer ${accessToken}` }
  });

  if (!userInfoResponse.ok) {
    throw new Error('Falha ao obter perfil da conta Google.');
  }

  const profile = (await userInfoResponse.json()) as { email?: string };
  const email = profile.email ?? 'sem-email';

  await setDriveSession({
    email,
    accessToken,
    expiryMs
  });

  await appendDiagnosticLog(`DRIVE_LINK_SUCCESS email=${email}`);
  return email;
}

export async function unlinkDriveAccount(): Promise<void> {
  const token = await getDriveAccessToken();
  if (token) {
    await fetch(`https://oauth2.googleapis.com/revoke?token=${encodeURIComponent(token)}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' }
    }).catch(() => undefined);
  }
  await clearDriveSession();
  await appendDiagnosticLog('DRIVE_UNLINK_SUCCESS');
}

async function ensureDateFolder(accessToken: string, folderName: string): Promise<string> {
  const query = encodeURIComponent(
    `mimeType='application/vnd.google-apps.folder' and trashed=false and name='${folderName.replace(/'/g, "\\'")}'`
  );

  const listResp = await fetch(`https://www.googleapis.com/drive/v3/files?q=${query}&fields=files(id,name)`, {
    headers: { Authorization: `Bearer ${accessToken}` }
  });

  if (!listResp.ok) {
    throw new Error('Falha ao listar pastas no Google Drive.');
  }

  const listed = (await listResp.json()) as { files?: Array<{ id: string }> };
  const existing = listed.files?.[0]?.id;
  if (existing) return existing;

  const createResp = await fetch('https://www.googleapis.com/drive/v3/files', {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${accessToken}`,
      'Content-Type': 'application/json'
    },
    body: JSON.stringify({
      name: folderName,
      mimeType: 'application/vnd.google-apps.folder'
    })
  });

  if (!createResp.ok) {
    throw new Error('Falha ao criar pasta no Google Drive.');
  }

  const created = (await createResp.json()) as { id: string };
  return created.id;
}

export async function uploadVideoToDrive(localUri: string, displayName: string): Promise<void> {
  const accessToken = await requireToken();

  const dateFolder = new Date().toISOString().slice(0, 10);
  const folderId = await ensureDateFolder(accessToken, dateFolder);

  const createResp = await fetch('https://www.googleapis.com/drive/v3/files?fields=id', {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${accessToken}`,
      'Content-Type': 'application/json'
    },
    body: JSON.stringify({
      name: displayName,
      parents: [folderId]
    })
  });

  if (!createResp.ok) {
    throw new Error('Falha ao criar metadata do arquivo no Drive.');
  }

  const created = (await createResp.json()) as { id: string };

  const uploadResult = await FileSystem.uploadAsync(
    `https://www.googleapis.com/upload/drive/v3/files/${created.id}?uploadType=media`,
    localUri,
    {
      uploadType: FileSystem.FileSystemUploadType.BINARY_CONTENT,
      httpMethod: 'PATCH',
      headers: {
        Authorization: `Bearer ${accessToken}`,
        'Content-Type': 'video/mp4'
      }
    }
  );

  if (uploadResult.status < 200 || uploadResult.status >= 300) {
    throw new Error(`Falha no upload para Drive. status=${uploadResult.status}`);
  }

  await appendDiagnosticLog(`DRIVE_UPLOAD_SUCCESS name=${displayName} uri=${localUri}`);
}
