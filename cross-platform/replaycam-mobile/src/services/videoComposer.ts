import * as FileSystem from 'expo-file-system';
import { FFmpegKit, ReturnCode } from 'ffmpeg-kit-react-native';
import type { SegmentInfo } from '../types/replay';
import { appendDiagnosticLog } from './logger';

const esc = (value: string) => value.replace(/'/g, "'\\''");

async function run(command: string) {
  const session = await FFmpegKit.execute(command);
  const returnCode = await session.getReturnCode();
  const logs = await session.getOutput();
  if (!ReturnCode.isSuccess(returnCode)) {
    throw new Error(`FFmpeg falhou: ${logs ?? 'sem logs'}`);
  }
}

export function selectReplayWindowSegments(segments: SegmentInfo[], targetSeconds = 20) {
  if (segments.length === 0) return { selected: [], trimFromFirstSec: 0 };

  const reversed = [...segments].reverse();
  const selected: SegmentInfo[] = [];
  let accumulated = 0;

  for (const segment of reversed) {
    selected.unshift(segment);
    accumulated += segment.durationSec;
    if (accumulated >= targetSeconds) break;
  }

  const trimFromFirstSec = Math.max(0, accumulated - targetSeconds);
  return { selected, trimFromFirstSec };
}

export async function composeSegmentsToMp4(params: {
  segments: SegmentInfo[];
  outputUri: string;
  trimFromFirstSec?: number;
}): Promise<void> {
  const { segments, outputUri, trimFromFirstSec = 0 } = params;
  if (segments.length === 0) throw new Error('Nenhum segmento para compor.');

  const workDir = `${FileSystem.cacheDirectory}compose_${Date.now()}/`;
  await FileSystem.makeDirectoryAsync(workDir, { intermediates: true });

  const normalizedUris: string[] = [];
  try {
    for (let index = 0; index < segments.length; index += 1) {
      const segment = segments[index];
      if (index === 0 && trimFromFirstSec > 0.01) {
        const trimmed = `${workDir}trimmed_first.mp4`;
        await run(`-y -ss ${trimFromFirstSec.toFixed(3)} -i '${esc(segment.uri)}' -c copy '${esc(trimmed)}'`);
        normalizedUris.push(trimmed);
      } else {
        normalizedUris.push(segment.uri);
      }
    }

    if (normalizedUris.length === 1) {
      await FileSystem.copyAsync({ from: normalizedUris[0], to: outputUri });
      return;
    }

    const listFile = `${workDir}inputs.txt`;
    const listBody = normalizedUris.map((uri) => `file '${uri.replace(/'/g, "'\\''")}'`).join('\n');
    await FileSystem.writeAsStringAsync(listFile, listBody);

    try {
      await run(`-y -f concat -safe 0 -i '${esc(listFile)}' -c copy '${esc(outputUri)}'`);
    } catch (copyErr) {
      await appendDiagnosticLog(`concat copy falhou; tentando re-encode: ${(copyErr as Error).message}`);
      await run(`-y -f concat -safe 0 -i '${esc(listFile)}' -c:v libx264 -preset veryfast -c:a aac '${esc(outputUri)}'`);
    }
  } finally {
    await FileSystem.deleteAsync(workDir, { idempotent: true }).catch(() => undefined);
  }
}
