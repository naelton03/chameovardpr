import * as FileSystem from 'expo-file-system';
import type { SegmentInfo } from '../types/replay';

const BUFFER_SECONDS = 20;

export class ReplayBuffer {
  private segments: SegmentInfo[] = [];

  addSegment(segment: SegmentInfo) {
    this.segments.push(segment);
    this.trimWindow();
  }

  allSegments() {
    return [...this.segments];
  }

  replaySegments() {
    return [...this.segments];
  }

  clear() {
    this.segments = [];
  }

  private trimWindow() {
    let total = this.segments.reduce((acc, item) => acc + item.durationSec, 0);
    while (this.segments.length > 1 && total > BUFFER_SECONDS) {
      const first = this.segments.shift();
      total -= first?.durationSec ?? 0;
    }
  }

  async deleteAllTempFiles() {
    const deletions = this.segments.map((segment) =>
      FileSystem.deleteAsync(segment.uri, { idempotent: true }).catch(() => undefined)
    );
    await Promise.all(deletions);
  }
}
