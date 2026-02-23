import { useMemo, useState } from 'react';
import { StyleSheet, Text, TextInput, View } from 'react-native';
import { CameraView } from 'expo-camera';
import { useReplayController } from '../state/useReplayController';
import { PrimaryButton } from '../components/PrimaryButton';
import { colors } from '../theme/colors';
import { formatClock } from '../utils/time';

export function ReplayScreen() {
  const [liveTitle, setLiveTitle] = useState('ReplayCam Live');
  const [livePrivacy, setLivePrivacy] = useState<'public' | 'unlisted' | 'private'>('unlisted');

  const {
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
    liveEnabled
  } = useReplayController();

  const selectedCamera = useMemo(
    () => cameraOptions.find((camera) => camera.id === selectedCameraId),
    [cameraOptions, selectedCameraId]
  );

  return (
    <View style={styles.root}>
      <CameraView style={styles.preview} facing="back" mute={false} ref={(ref) => bindRecorder(ref as never)} />

      <View style={styles.topInfoContainer}>
        <Text style={styles.brand}>ReplayCam</Text>
        <Text style={styles.timer}>{formatClock(elapsedSeconds)}</Text>
        <Text style={[styles.status, status.isError ? styles.statusError : null]}>{status.message}</Text>
      </View>

      <View style={styles.bottomPanel}>
        <Text style={styles.panelLabel}>Câmera traseira</Text>
        {cameraOptions.map((camera) => (
          <PrimaryButton
            key={camera.id}
            label={`${camera.label} • ${camera.maxFps}fps • ${camera.maxResolution}`}
            onPress={() => setSelectedCameraId(camera.id)}
            tone={selectedCameraId === camera.id ? 'success' : 'primary'}
            disabled={isRecording}
          />
        ))}
        {selectedCamera && (
          <Text style={styles.smallInfo}>
            Zoom {selectedCamera.maxZoom}x • Multi-câmera: {selectedCamera.isLogicalMultiCamera ? 'Sim' : 'Não'}
          </Text>
        )}

        <View style={styles.row}>
          <PrimaryButton label="Abrir galeria/pasta" onPress={openGallery} tone="primary" />
          <PrimaryButton
            label={isLive ? 'Encerrar LIVE' : 'LIVE'}
            onPress={() => (isLive ? stopLive() : startLive({ title: liveTitle, privacy: livePrivacy }))}
            tone={isLive ? 'danger' : 'primary'}
            disabled={!liveEnabled}
          />
        </View>

        <TextInput
          value={liveTitle}
          onChangeText={setLiveTitle}
          style={styles.input}
          placeholder="Título da LIVE"
          placeholderTextColor={colors.textSecondary}
        />

        <View style={styles.row}>
          {(['public', 'unlisted', 'private'] as const).map((privacy) => (
            <PrimaryButton
              key={privacy}
              label={privacy.toUpperCase()}
              onPress={() => setLivePrivacy(privacy)}
              tone={livePrivacy === privacy ? 'success' : 'primary'}
              disabled={!liveEnabled}
            />
          ))}
        </View>

        <View style={styles.row}>
          <PrimaryButton label="Iniciar" onPress={startRecording} disabled={isRecording} tone="success" />
          <PrimaryButton label="Replay 20s" onPress={saveReplay} disabled={!isRecording} tone="primary" />
          <PrimaryButton label="Parar" onPress={stopAndSaveSession} disabled={!isRecording} tone="danger" />
        </View>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  root: {
    flex: 1,
    backgroundColor: '#000'
  },
  preview: {
    ...StyleSheet.absoluteFillObject
  },
  topInfoContainer: {
    marginTop: 56,
    marginHorizontal: 16,
    padding: 12,
    borderRadius: 12,
    backgroundColor: 'rgba(20, 31, 48, 0.72)',
    gap: 8
  },
  brand: {
    color: colors.textPrimary,
    fontWeight: '800',
    fontSize: 18
  },
  timer: {
    alignSelf: 'flex-start',
    color: colors.warning,
    fontWeight: '900',
    fontSize: 26
  },
  status: {
    color: colors.textPrimary,
    fontSize: 14
  },
  statusError: {
    color: colors.danger
  },
  bottomPanel: {
    position: 'absolute',
    left: 16,
    right: 16,
    bottom: 16,
    borderRadius: 14,
    backgroundColor: 'rgba(16, 25, 41, 0.92)',
    padding: 12,
    gap: 8
  },
  panelLabel: {
    color: colors.textPrimary,
    fontWeight: '700'
  },
  row: {
    flexDirection: 'row',
    gap: 8
  },
  smallInfo: {
    color: colors.textSecondary,
    fontSize: 12
  },
  input: {
    backgroundColor: '#111A2E',
    borderRadius: 10,
    color: colors.textPrimary,
    paddingHorizontal: 12,
    paddingVertical: 10
  }
});
