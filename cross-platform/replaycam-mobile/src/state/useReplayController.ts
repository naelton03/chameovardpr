import { useEffect, useMemo, useRef, useState } from 'react';
import { AppState } from 'react-native';
import { ReplayEngine } from '../services/replayEngine';
import type { CameraOption, LiveConfig, ReplayStatus } from '../types/replay';
import { requestAllPermissions } from '../services/permissions';
import { appendDiagnosticLog, diagnosticsFilePath } from '../services/logger';
import { openVideoGalleryQuickAccess } from '../services/gallery';
import { FeatureToggles } from '../config/featureToggles';

interface CameraRecorder {
  recordAsync: (options?: { maxDuration?: number; mute?: boolean }) => Promise<{ uri: string }>;
  stopRecording: () => void;
}

export function useReplayController() {
  const engine = useMemo(() => new ReplayEngine(), []);
  const timerRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const recorderRef = useRef<CameraRecorder | null>(null);

  const [isRecording, setIsRecording] = useState(false);
  const [isLive, setIsLive] = useState(false);
  const [elapsedSeconds, setElapsedSeconds] = useState(0);
  const [status, setStatus] = useState<ReplayStatus>({ message: 'Pronto para gravar.' });
  const [cameraOptions, setCameraOptions] = useState<CameraOption[]>([]);
  const [selectedCameraId, setSelectedCameraId] = useState<string>('rear-main');

  useEffect(() => {
    engine.listRearCameras().then((cameras) => {
      setCameraOptions(cameras);
      if (cameras[0]) setSelectedCameraId(cameras[0].id);
    });
  }, [engine]);

  useEffect(() => {
    const sub = AppState.addEventListener('change', async (nextState) => {
      if (!recorderRef.current) return;
      if (nextState !== 'active' && engine.recording()) {
        await engine.pauseContinuousRecording(recorderRef.current);
        setStatus({ message: 'Gravação pausada no background. Retomando ao voltar.' });
      }
      if (nextState === 'active' && isRecording && !engine.recording()) {
        await engine.resumeContinuousRecording(recorderRef.current);
        setStatus({ message: 'Gravação contínua retomada no foreground.' });
      }
    });
    return () => sub.remove();
  }, [engine, isRecording]);

  useEffect(() => () => {
    if (timerRef.current) clearInterval(timerRef.current);
  }, []);

  const bindRecorder = (recorder: CameraRecorder | null) => {
    recorderRef.current = recorder;
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

    await engine.startContinuousRecording(selectedCameraId, recorderRef.current);
    setElapsedSeconds(0);
    setIsRecording(true);
    setStatus({ message: 'Gravação contínua ativa (buffer de replay 20s).' });

    if (timerRef.current) clearInterval(timerRef.current);
    timerRef.current = setInterval(() => setElapsedSeconds((cur) => cur + 1), 1000);
  };

  const saveReplay = async () => {
    try {
      const fileName = await engine.saveReplayWindow();
      setStatus({ message: `Replay salvo: ${fileName}` });
    } catch (error) {
      setStatus({ message: `Erro ao salvar replay: ${(error as Error).message}`, isError: true });
    }
  };

  const stopAndSaveSession = async () => {
    if (!recorderRef.current) return;
    try {
      const fileName = await engine.stopAndExportSession(recorderRef.current);
      setIsRecording(false);
      if (timerRef.current) {
        clearInterval(timerRef.current);
        timerRef.current = null;
      }
      setStatus({ message: `Sessão completa exportada: ${fileName}` });
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
      const diagnosticsPath = diagnosticsFilePath();
      setStatus({ message: `Não foi possível abrir galeria. Caminho de diagnóstico: ${diagnosticsPath}`, isError: true });
    }
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
    liveEnabled: FeatureToggles.isLiveEnabled
  };
}
