import { useEffect, useMemo, useRef, useState } from 'react';
import { AppState } from 'react-native';
import { ReplayEngine } from '../services/replayEngine';
import type { CameraOption, LiveConfig, RecordingPreference, RecordingQuality, ReplayPlayType, ReplayStatus } from '../types/replay';
import { requestAllPermissions } from '../services/permissions';
import { appendDiagnosticLog, diagnosticsFilePath } from '../services/logger';
import { copyPathToClipboard, openVideoGalleryQuickAccess } from '../services/gallery';
import { FeatureToggles } from '../config/featureToggles';
import {
  getAutoUploadEnabled,
  getRecordingPreference,
  getReplayDurationSec,
  setAutoUploadEnabled,
  setRecordingPreference,
  setReplayDurationSec
} from '../services/autoUploadPrefs';
import { isDriveLinked, linkDriveAccount, linkedDriveEmail, unlinkDriveAccount, uploadVideoToDrive } from '../services/driveUpload';

interface CameraRecorder {
  recordAsync: (options?: { maxDuration?: number; mute?: boolean; videoBitrate?: number }) => Promise<{ uri: string }>;
  stopRecording: () => void;
}

function buildZoomRatios(maxZoomRatio: number): number[] {
  const levels = [1, 1.5, 2, 3, 4, 5, 6, 8, 10];
  const filtered = levels.filter((value) => value <= maxZoomRatio + 0.001);
  if (!filtered.includes(1)) filtered.unshift(1);
  const roundedMax = Math.round(maxZoomRatio * 10) / 10;
  if (!filtered.some((v) => Math.abs(v - roundedMax) < 0.01)) filtered.push(roundedMax);
  return [...new Set(filtered)].sort((a, b) => a - b);
}

function parseResolutionHeight(resolution: string): number {
  const parts = resolution.toLowerCase().split('x').map((value) => Number(value));
  if (parts.length !== 2 || Number.isNaN(parts[1])) return 1080;
  return parts[1];
}

function getSupportedQualityOptions(maxResolution: string): RecordingQuality[] {
  const maxHeight = parseResolutionHeight(maxResolution);
  const options: RecordingQuality[] = [];
  if (maxHeight >= 2160) options.push('4K');
  if (maxHeight >= 1080) options.push('1080');
  if (maxHeight >= 720) options.push('720');
  if (!options.length) options.push('720');
  return options;
}

function getSupportedFpsOptions(maxFps: number): Array<30 | 60> {
  const options: Array<30 | 60> = [];
  if (maxFps >= 30) options.push(30);
  if (maxFps >= 60) options.push(60);
  if (!options.length) options.push(30);
  return options;
}

export function useReplayController() {
  const engine = useMemo(() => new ReplayEngine(), []);
  const timerRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const recorderRef = useRef<CameraRecorder | null>(null);
  const savingReplayLockRef = useRef(false);

  const [isRecording, setIsRecording] = useState(false);
  const [isLive, setIsLive] = useState(false);
  const [elapsedSeconds, setElapsedSeconds] = useState(0);
  const [status, setStatus] = useState<ReplayStatus>({ message: 'Pronto para gravar. Zoom 1.0x.' });
  const [cameraOptions, setCameraOptions] = useState<CameraOption[]>([]);
  const [selectedCameraId, setSelectedCameraId] = useState<string>('rear-main');
  const [lastOutputPath, setLastOutputPath] = useState<string>('');
  const [autoUploadEnabled, setAutoUploadEnabledState] = useState(false);
  const [driveEmail, setDriveEmail] = useState<string | null>(null);
  const [isSavingReplay, setIsSavingReplay] = useState(false);
  const [zoomRatios, setZoomRatios] = useState<number[]>([1]);
  const [selectedZoomRatio, setSelectedZoomRatio] = useState<number>(1);
  const [replayDurationSec, setReplayDurationSecState] = useState<number>(20);
  const [supportedFpsOptions, setSupportedFpsOptions] = useState<Array<30 | 60>>([30]);
  const [supportedQualityOptions, setSupportedQualityOptions] = useState<RecordingQuality[]>(['1080', '720']);
  const [recordingPreference, setRecordingPreferenceState] = useState<RecordingPreference>({ fps: 30, quality: '1080' });

  useEffect(() => {
    engine.listRearCameras().then((cameras) => {
      setCameraOptions(cameras);
      if (cameras[0]) setSelectedCameraId(cameras[0].id);
    });
  }, [engine]);

  useEffect(() => {
    (async () => {
      const [enabled, email, replaySeconds, savedRecordingPreference] = await Promise.all([
        getAutoUploadEnabled(),
        linkedDriveEmail(),
        getReplayDurationSec(),
        getRecordingPreference()
      ]);
      setAutoUploadEnabledState(enabled);
      setDriveEmail(email);
      setReplayDurationSecState(replaySeconds);
      setRecordingPreferenceState(savedRecordingPreference);
    })();
  }, []);

  useEffect(() => {
    const selected = cameraOptions.find((camera) => camera.id === selectedCameraId);
    const maxZoom = selected?.maxZoom ?? 1;
    const levels = buildZoomRatios(maxZoom);
    setZoomRatios(levels);
    setSelectedZoomRatio((current) => {
      const target = Math.min(Math.max(current, 1), maxZoom);
      const closest = levels.reduce((best, value) =>
        Math.abs(value - target) < Math.abs(best - target) ? value : best
      , levels[0]);
      return closest;
    });

    const fpsOptions = getSupportedFpsOptions(selected?.maxFps ?? 30);
    const qualityOptions = getSupportedQualityOptions(selected?.maxResolution ?? '1920x1080');
    setSupportedFpsOptions(fpsOptions);
    setSupportedQualityOptions(qualityOptions);

    setRecordingPreferenceState((current) => {
      const next: RecordingPreference = {
        fps: fpsOptions.includes(current.fps) ? current.fps : fpsOptions[0],
        quality: qualityOptions.includes(current.quality) ? current.quality : qualityOptions[0]
      };
      setRecordingPreference(next).catch(() => undefined);
      return next;
    });
  }, [cameraOptions, selectedCameraId]);

  useEffect(() => {
    const sub = AppState.addEventListener('change', async (nextState) => {
      if (!recorderRef.current) return;
      if (nextState !== 'active' && engine.recording()) {
        await engine.pauseContinuousRecording(recorderRef.current);
        setStatus({ message: 'Gravação pausada no background. Retomando ao voltar.' });
      }
      if (nextState === 'active' && isRecording && !engine.recording()) {
        const permissions = await requestAllPermissions();
        if (!permissions.camera || !permissions.microphone) {
          setStatus({ message: 'Retomada bloqueada: permissões revogadas.', isError: true });
          await appendDiagnosticLog('resume blocked due to revoked permissions');
          return;
        }
        await engine.resumeContinuousRecording(recorderRef.current);
        setStatus({ message: `Gravação contínua retomada no foreground. Zoom ${selectedZoomRatio.toFixed(1)}x.` });
      }
    });
    return () => sub.remove();
  }, [engine, isRecording, selectedZoomRatio]);

  useEffect(() => () => {
    if (timerRef.current) clearInterval(timerRef.current);
  }, []);

  const bindRecorder = (recorder: CameraRecorder | null) => {
    recorderRef.current = recorder;
  };

  const updateZoomRatio = (ratio: number) => {
    setSelectedZoomRatio(ratio);
    setStatus({ message: `Status: zoom ${ratio.toFixed(1)}x` });
  };

  const updateRecordingFps = async (fps: 30 | 60) => {
    if (!supportedFpsOptions.includes(fps)) return;
    const next = { ...recordingPreference, fps };
    setRecordingPreferenceState(next);
    await setRecordingPreference(next);
    setStatus({ message: `Configuração aplicada: ${next.quality} ${next.fps}fps` });
  };

  const updateRecordingQuality = async (quality: RecordingQuality) => {
    if (!supportedQualityOptions.includes(quality)) return;
    const next = { ...recordingPreference, quality };
    setRecordingPreferenceState(next);
    await setRecordingPreference(next);
    setStatus({ message: `Configuração aplicada: ${next.quality} ${next.fps}fps` });
  };

  const startRecording = async () => {
    if (!recorderRef.current) {
      setStatus({ message: 'Preview de câmera indisponível.', isError: true });
      return;
    }

    const permissions = await requestAllPermissions();
    if (!permissions.camera || !permissions.microphone || !permissions.mediaLibrary) {
      setStatus({ message: 'Permissões obrigatórias não concedidas.', isError: true });
      await appendDiagnosticLog(`permission denied camera=${permissions.camera} mic=${permissions.microphone} media=${permissions.mediaLibrary}`);
      return;
    }

    await engine.startContinuousRecording(selectedCameraId, recorderRef.current, recordingPreference);
    setElapsedSeconds(0);
    setIsRecording(true);
    setStatus({ message: `Gravação contínua ativa (${recordingPreference.quality} ${recordingPreference.fps}fps / buffer ${replayDurationSec}s). Zoom ${selectedZoomRatio.toFixed(1)}x.` });

    if (timerRef.current) clearInterval(timerRef.current);
    timerRef.current = setInterval(() => setElapsedSeconds((cur) => cur + 1), 1000);
  };

  const maybeUploadVideoToDrive = async (localUri: string, displayName: string) => {
    if (!autoUploadEnabled) return;
    if (!(await isDriveLinked())) {
      setStatus({ message: 'Status: upload automático ativo, mas sem conta Drive vinculada', isError: true });
      return;
    }
    try {
      await uploadVideoToDrive(localUri, displayName);
      setStatus({ message: 'Status: vídeo enviado ao Google Drive' });
    } catch (error) {
      setStatus({ message: `Erro upload Drive: ${(error as Error).message}`, isError: true });
    }
  };

  const saveReplay = async (playType?: ReplayPlayType) => {
    if (savingReplayLockRef.current || isSavingReplay) return;
    savingReplayLockRef.current = true;
    setIsSavingReplay(true);
    try {
      const saved = await engine.saveReplayWindow(replayDurationSec, playType);
      setLastOutputPath(saved.localUri);
      setStatus({ message: `Replay salvo${playType ? ` (${playType})` : ''} (${replayDurationSec}s): ${saved.fileName} (${saved.album})` });
      await maybeUploadVideoToDrive(saved.localUri, saved.fileName);
    } catch (error) {
      setStatus({ message: `Erro ao salvar replay: ${(error as Error).message}`, isError: true });
    } finally {
      savingReplayLockRef.current = false;
      setIsSavingReplay(false);
    }
  };

  const stopAndSaveSession = async () => {
    if (!recorderRef.current) return;
    try {
      const saved = await engine.stopAndExportSession(recorderRef.current);
      setLastOutputPath(saved.localUri);
      setIsRecording(false);
      if (timerRef.current) {
        clearInterval(timerRef.current);
        timerRef.current = null;
      }
      setStatus({ message: `Sessão completa exportada: ${saved.fileName} (${saved.album})` });
      await maybeUploadVideoToDrive(saved.localUri, saved.fileName);
    } catch (error) {
      setStatus({ message: `Erro ao exportar sessão: ${(error as Error).message}`, isError: true });
    }
  };

  const startLive = async (config: LiveConfig) => {
    if (!FeatureToggles.isLiveEnabled) {
      setStatus({ message: 'LIVE desativada por feature flag (como no Android antigo).', isError: true });
      return;
    }
    await engine.startLive(config);
    setIsLive(true);
    setStatus({ message: `LIVE iniciada: ${config.title}` });
  };

  const stopLive = async () => {
    await engine.stopLive();
    setIsLive(false);
    setStatus({ message: 'LIVE encerrada.' });
  };

  const openGallery = async () => {
    try {
      await openVideoGalleryQuickAccess();
      setStatus({ message: 'Galeria/pasta de vídeos aberta.' });
    } catch {
      const fallbackPath = lastOutputPath || diagnosticsFilePath();
      await copyPathToClipboard(fallbackPath);
      setStatus({ message: `Não foi possível abrir galeria. Caminho copiado: ${fallbackPath}`, isError: true });
    }
  };

  const toggleAutoUpload = async (enabled: boolean) => {
    setAutoUploadEnabledState(enabled);
    await setAutoUploadEnabled(enabled);
  };

  const updateReplayDurationSec = async (value: number) => {
    setReplayDurationSecState(value);
    await setReplayDurationSec(value);
    setStatus({ message: `Configuração aplicada: replay ${value}s` });
  };

  const toggleDriveLink = async () => {
    if (driveEmail) {
      await unlinkDriveAccount();
      setDriveEmail(null);
      setStatus({ message: 'Conta Google Drive desvinculada' });
      return;
    }
    const email = await linkDriveAccount();
    setDriveEmail(email);
    setStatus({ message: `Status: conta Drive vinculada (${email})` });
  };

  return {
    isRecording,
    isLive,
    elapsedSeconds,
    status,
    cameraOptions,
    selectedCameraId,
    setSelectedCameraId,
    bindRecorder,
    startRecording,
    saveReplay,
    stopAndSaveSession,
    startLive,
    stopLive,
    openGallery,
    lastOutputPath,
    autoUploadEnabled,
    driveEmail,
    isSavingReplay,
    toggleAutoUpload,
    replayDurationSec,
    updateReplayDurationSec,
    toggleDriveLink,
    zoomRatios,
    selectedZoomRatio,
    setSelectedZoomRatio: updateZoomRatio,
    supportedFpsOptions,
    supportedQualityOptions,
    recordingPreference,
    updateRecordingFps,
    updateRecordingQuality,
    liveEnabled: FeatureToggles.isLiveEnabled
  };
}
