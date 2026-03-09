import * as FileSystem from 'expo-file-system';
import type { SegmentInfo } from '../types/replay';
import { appendDiagnosticLog } from './logger';

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

  if (segments.length === 1 && trimFromFirstSec < 0.01) {
    await FileSystem.copyAsync({ from: segments[0].uri, to: outputUri });
    return;
  }

  const fallbackSegment = segments[segments.length - 1];
  await appendDiagnosticLog(
    `compose fallback sem ffmpeg: segmentos=${segments.length} trimFromFirst=${trimFromFirstSec.toFixed(2)}s; copiando apenas o último segmento`
  );
  await FileSystem.copyAsync({ from: fallbackSegment.uri, to: outputUri });
}
