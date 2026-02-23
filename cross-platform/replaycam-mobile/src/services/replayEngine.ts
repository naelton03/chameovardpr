import type { CameraOption, LiveConfig } from '../types/replay';

const wait = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

export class ReplayEngine {
  private isRecording = false;

  async listRearCameras(): Promise<CameraOption[]> {
    return [
      {
        id: 'rear-main',
        label: 'Câmera traseira principal',
        maxFps: 60,
        maxResolution: '3840x2160',
        maxZoom: 8,
        isLogicalMultiCamera: true
      }
    ];
  }

  async startContinuousRecording(cameraId: string): Promise<void> {
    if (this.isRecording) return;
    this.isRecording = true;
    await wait(250);
    console.log(`Recording started on camera ${cameraId}`);
  }

  async saveReplayWindow(seconds = 20): Promise<string> {
    await wait(200);
    return `replay_${Date.now()}_${seconds}s.mp4`;
  }

  async stopAndExportSession(): Promise<string> {
    await wait(300);
    this.isRecording = false;
    return `recording_${Date.now()}.mp4`;
  }

  async startLive(_config: LiveConfig): Promise<void> {
    await wait(350);
  }

  async stopLive(): Promise<void> {
    await wait(100);
  }
}
