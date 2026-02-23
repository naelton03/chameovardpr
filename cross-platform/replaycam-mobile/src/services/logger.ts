import * as FileSystem from 'expo-file-system';

const LOG_DIR = `${FileSystem.documentDirectory ?? ''}replaycam-logs/`;
const LOG_FILE = `${LOG_DIR}diagnostics.log`;

async function ensureFile() {
  if (!FileSystem.documentDirectory) return;
  const dir = await FileSystem.getInfoAsync(LOG_DIR);
  if (!dir.exists) {
    await FileSystem.makeDirectoryAsync(LOG_DIR, { intermediates: true });
  }
  const file = await FileSystem.getInfoAsync(LOG_FILE);
  if (!file.exists) {
    await FileSystem.writeAsStringAsync(LOG_FILE, '');
  }
}

export async function appendDiagnosticLog(line: string) {
  const timestamp = new Date().toISOString();
  const message = `[${timestamp}] ${line}\n`;
  console.log(message.trim());
  if (!FileSystem.documentDirectory) return;
  await ensureFile();
  await FileSystem.writeAsStringAsync(LOG_FILE, message, {
    encoding: FileSystem.EncodingType.UTF8,
    append: true
  });
}

export function diagnosticsFilePath() {
  return LOG_FILE;
}
