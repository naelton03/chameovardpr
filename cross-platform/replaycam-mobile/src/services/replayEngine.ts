import * as FileSystem from 'expo-file-system';
import { timestampTag } from '../utils/time';
import type { CameraOption, LiveConfig, SavedVideoInfo, SegmentInfo } from '../types/replay';
import { ReplayBuffer } from './replayBuffer';
import { appendDiagnosticLog } from './logger';
import { saveToReplayAlbum } from './gallery';

interface CameraRecorder {
  recordAsync: (options?: { maxDuration?: number; mute?: boolean }) => Promise<{ uri: string }>;
  stopRecording: () => void;
}

const SEGMENT_SECONDS = 5;

export class ReplayEngine {
  private isRecording = false;
  private isLive = false;
  private recordingTask: Promise<void> | null = null;
  private buffer = new ReplayBuffer();
  private sessionSegments: SegmentInfo[] = [];

  async listRearCameras(): Promise<CameraOption[]> {
    return [
      {
        id: 'rear-main',
        label: 'Câmera traseira principal',
        maxFps: 60,
        maxResolution: '1920x1080',
        maxZoom: 8,
        isLogicalMultiCamera: true
      }
    ];
  }

  async startContinuousRecording(cameraId: string, recorder: CameraRecorder): Promise<void> {
    if (this.isRecording) return;
    this.isRecording = true;
    this.buffer.clear();
    this.sessionSegments = [];
    await appendDiagnosticLog(`startContinuousRecording camera=${cameraId}`);

    this.recordingTask = this.captureLoop(recorder).finally(() => {
      this.recordingTask = null;
    });
  }

  private async captureLoop(recorder: CameraRecorder) {
    while (this.isRecording) {
      const start = Date.now();
      const result = await recorder.recordAsync({ maxDuration: SEGMENT_SECONDS, mute: false });
      const end = Date.now();
      const segment: SegmentInfo = {
        uri: result.uri,
        startedAt: start,
        endedAt: end,
        durationSec: Math.max(1, Math.round((end - start) / 1000))
      };

      this.sessionSegments.push(segment);
      this.buffer.addSegment(segment);
      await appendDiagnosticLog(`segment captured uri=${segment.uri} duration=${segment.durationSec}s`);
    }
  }

  async pauseContinuousRecording(recorder: CameraRecorder): Promise<void> {
    if (!this.isRecording) return;
    this.isRecording = false;
    recorder.stopRecording();
    await this.recordingTask;
    await appendDiagnosticLog('recording paused (background)');
  }

  async resumeContinuousRecording(recorder: CameraRecorder): Promise<void> {
    if (this.isRecording) return;
    this.isRecording = true;
    await appendDiagnosticLog('recording resumed (foreground)');
    this.recordingTask = this.captureLoop(recorder).finally(() => {
      this.recordingTask = null;
    });
  }

  async saveReplayWindow(): Promise<SavedVideoInfo> {
    const segments = this.buffer.replaySegments();
    if (segments.length === 0) {
      throw new Error('Sem segmentos de replay para salvar.');
    }

    const latest = segments[segments.length - 1];
    const fileName = `replay_${timestampTag()}.mp4`;
    const localUri = `${FileSystem.cacheDirectory}${fileName}`;
    await FileSystem.copyAsync({ from: latest.uri, to: localUri });
    const album = await saveToReplayAlbum(localUri);

    if (segments.length > 1) {
      await appendDiagnosticLog('Replay salvo usando fallback (último segmento). Para concat real de 20s, adicionar compositor nativo.');
    }

    return { fileName, localUri, album };
  }

  async stopAndExportSession(recorder: CameraRecorder): Promise<SavedVideoInfo> {
    if (this.isRecording) {
      this.isRecording = false;
      recorder.stopRecording();
      await this.recordingTask;
    }

    if (this.sessionSegments.length === 0) {
      throw new Error('Não há segmentos na sessão para exportar.');
    }

    const last = this.sessionSegments[this.sessionSegments.length - 1];
    const fileName = `recording_${timestampTag()}.mp4`;
    const localUri = `${FileSystem.cacheDirectory}${fileName}`;
    await FileSystem.copyAsync({ from: last.uri, to: localUri });
    const album = await saveToReplayAlbum(localUri);
    await this.buffer.deleteAllTempFiles();
    await appendDiagnosticLog(`session exported file=${fileName} segments=${this.sessionSegments.length}`);
    return { fileName, localUri, album };
  }

  async startLive(config: LiveConfig): Promise<void> {
    this.isLive = true;
    await appendDiagnosticLog(`LIVE start requested title=${config.title} privacy=${config.privacy}`);
  }

  async stopLive(): Promise<void> {
    this.isLive = false;
    await appendDiagnosticLog('LIVE stop requested');
  }

  recording() {
    return this.isRecording;
  }

  live() {
    return this.isLive;
  }
}
