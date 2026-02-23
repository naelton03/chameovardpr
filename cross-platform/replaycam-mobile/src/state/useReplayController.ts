import { useEffect, useMemo, useRef, useState } from 'react';
import { ReplayEngine } from '../services/replayEngine';
import type { CameraOption, LiveConfig, ReplayStatus } from '../types/replay';

export function useReplayController() {
  const engine = useMemo(() => new ReplayEngine(), []);
  const timerRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const [isRecording, setIsRecording] = useState(false);
  const [isLive, setIsLive] = useState(false);
  const [elapsedSeconds, setElapsedSeconds] = useState(0);
  const [status, setStatus] = useState<ReplayStatus>({ message: 'Pronto para gravar.' });
  const [cameraOptions, setCameraOptions] = useState<CameraOption[]>([]);
  const [selectedCameraId, setSelectedCameraId] = useState<string>('');

  useEffect(() => {
    engine.listRearCameras().then((cameras) => {
      setCameraOptions(cameras);
      setSelectedCameraId(cameras[0]?.id ?? '');
    });
  }, [engine]);

  useEffect(() => () => {
    if (timerRef.current) {
      clearInterval(timerRef.current);
    }
  }, []);

  const startRecording = async () => {
    if (!selectedCameraId) {
      setStatus({ message: 'Nenhuma câmera traseira disponível.', isError: true });
      return;
    }

    await engine.startContinuousRecording(selectedCameraId);
    setElapsedSeconds(0);
    setIsRecording(true);
    setStatus({ message: 'Gravação contínua ativa (buffer de replay 20s).' });

    timerRef.current = setInterval(() => {
      setElapsedSeconds((current) => current + 1);
    }, 1000);
  };

  const saveReplay = async () => {
    const fileName = await engine.saveReplayWindow(20);
    setStatus({ message: `Replay salvo: ${fileName}` });
  };

  const stopAndSaveSession = async () => {
    const fileName = await engine.stopAndExportSession();
    setIsRecording(false);
    if (timerRef.current) {
      clearInterval(timerRef.current);
      timerRef.current = null;
    }
    setStatus({ message: `Sessão completa exportada: ${fileName}` });
  };

  const startLive = async (config: LiveConfig) => {
    await engine.startLive(config);
    setIsLive(true);
    setStatus({ message: `LIVE iniciada: ${config.title}` });
  };

  const stopLive = async () => {
    await engine.stopLive();
    setIsLive(false);
    setStatus({ message: 'LIVE encerrada.' });
  };

  return {
    isRecording,
    isLive,
    elapsedSeconds,
    status,
    cameraOptions,
    selectedCameraId,
    setSelectedCameraId,
    startRecording,
    saveReplay,
    stopAndSaveSession,
    startLive,
    stopLive
  };
}
