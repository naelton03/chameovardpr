import * as FileSystem from 'expo-file-system';
import { timestampTag } from '../utils/time';
import type { CameraOption, LiveConfig, SavedVideoInfo, SegmentInfo } from '../types/replay';
import { ReplayBuffer } from './replayBuffer';
import { appendDiagnosticLog } from './logger';
import { saveToReplayAlbum } from './gallery';
import { composeSegmentsToMp4, selectReplayWindowSegments } from './videoComposer';

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
  private activeRecorder: CameraRecorder | null = null;
  private boundaryPromise: Promise<void> | null = null;

  private completedSegmentsCount() {
    return this.sessionSegments.length;
  }

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
    this.activeRecorder = recorder;
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
      try {
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
      } catch (error) {
        if (!this.isRecording) {
          break;
        }
        await appendDiagnosticLog(`segment capture interruption: ${(error as Error).message}`);
      }
    }
  }

  async pauseContinuousRecording(recorder: CameraRecorder): Promise<void> {
    if (!this.isRecording) return;
    this.isRecording = false;
    this.activeRecorder = null;
    recorder.stopRecording();
    await this.recordingTask;
    await appendDiagnosticLog('recording paused (background)');
  }

  async resumeContinuousRecording(recorder: CameraRecorder): Promise<void> {
    if (this.isRecording) return;
    this.isRecording = true;
    this.activeRecorder = recorder;
    await appendDiagnosticLog('recording resumed (foreground)');
    this.recordingTask = this.captureLoop(recorder).finally(() => {
      this.recordingTask = null;
    });
  }

  async saveReplayWindow(): Promise<SavedVideoInfo> {
    await this.forceSegmentBoundaryIfRecording();

    const buffered = this.buffer.replaySegments();
    if (buffered.length === 0) {
      throw new Error('Sem segmentos de replay para salvar.');
    }

    const { selected, trimFromFirstSec } = selectReplayWindowSegments(buffered, 20);
    const selectedDuration = selected.reduce((acc, item) => acc + item.durationSec, 0);
    const expectedDuration = Math.min(20, selectedDuration);

    const fileName = `replay_${timestampTag()}.mp4`;
    const localUri = `${FileSystem.cacheDirectory}${fileName}`;

    await composeSegmentsToMp4({
      segments: selected,
      outputUri: localUri,
      trimFromFirstSec
    });

    await appendDiagnosticLog(
      `replay composed expectedDuration=${expectedDuration}s selectedSegments=${selected.length} trimFromFirst=${trimFromFirstSec.toFixed(2)}s`
    );

    const album = await saveToReplayAlbum(localUri);
    return { fileName, localUri, album };
  }

  async forceSegmentBoundaryIfRecording(): Promise<void> {
    if (!this.isRecording || !this.activeRecorder) return;
    if (this.boundaryPromise) {
      await this.boundaryPromise;
      return;
    }

    this.boundaryPromise = (async () => {
      const before = this.completedSegmentsCount();
      this.activeRecorder?.stopRecording();

      const startedAt = Date.now();
      while (Date.now() - startedAt < 3000) {
        if (this.completedSegmentsCount() > before) return;
        await new Promise((resolve) => setTimeout(resolve, 50));
      }

      await appendDiagnosticLog('forceSegmentBoundary timeout: replay seguirá com segmentos já finalizados');
    })();

    try {
      await this.boundaryPromise;
    } finally {
      this.boundaryPromise = null;
    }
  }

  async stopAndExportSession(recorder: CameraRecorder): Promise<SavedVideoInfo> {
    if (this.isRecording) {
      this.isRecording = false;
      this.activeRecorder = null;
      recorder.stopRecording();
      await this.recordingTask;
    }

    if (this.sessionSegments.length === 0) {
      throw new Error('Não há segmentos na sessão para exportar.');
    }

    const fileName = `recording_${timestampTag()}.mp4`;
    const localUri = `${FileSystem.cacheDirectory}${fileName}`;

    await composeSegmentsToMp4({
      segments: this.sessionSegments,
      outputUri: localUri,
      trimFromFirstSec: 0
    });

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
