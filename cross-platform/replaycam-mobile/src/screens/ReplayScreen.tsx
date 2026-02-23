import { useMemo, useState } from 'react';
import { SafeAreaView, ScrollView, StyleSheet, Text, TextInput, View } from 'react-native';
import { useReplayController } from '../state/useReplayController';
import { PrimaryButton } from '../components/PrimaryButton';
import { colors } from '../theme/colors';

const formatClock = (value: number) => {
  const minutes = String(Math.floor(value / 60)).padStart(2, '0');
  const seconds = String(value % 60).padStart(2, '0');
  return `${minutes}:${seconds}`;
};

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
    startRecording,
    saveReplay,
    stopAndSaveSession,
    startLive,
    stopLive
  } = useReplayController();

  const selectedCamera = useMemo(
    () => cameraOptions.find((camera) => camera.id === selectedCameraId),
    [cameraOptions, selectedCameraId]
  );

  return (
    <SafeAreaView style={styles.safeArea}>
      <ScrollView contentContainerStyle={styles.content}>
        <Text style={styles.title}>ReplayCam • Cross-Platform</Text>
        <Text style={styles.subtitle}>Android + iOS com buffer contínuo e replay de 20 segundos.</Text>

        <View style={styles.panel}>
          <Text style={styles.panelLabel}>Status</Text>
          <Text style={styles.status}>{status.message}</Text>
          <Text style={styles.timer}>{formatClock(elapsedSeconds)}</Text>
        </View>

        <View style={styles.panel}>
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
              Zoom {selectedCamera.maxZoom}x • Multi-câmera:{' '}
              {selectedCamera.isLogicalMultiCamera ? 'Sim' : 'Não'}
            </Text>
          )}
        </View>

        <View style={styles.panel}>
          <Text style={styles.panelLabel}>Controles de gravação</Text>
          <PrimaryButton label="Começar gravação contínua" onPress={startRecording} disabled={isRecording} />
          <PrimaryButton label="Salvar replay (20s)" onPress={saveReplay} disabled={!isRecording} tone="success" />
          <PrimaryButton
            label="Parar e salvar sessão completa"
            onPress={stopAndSaveSession}
            disabled={!isRecording}
            tone="danger"
          />
        </View>

        <View style={styles.panel}>
          <Text style={styles.panelLabel}>LIVE YouTube / RTMP</Text>
          <TextInput value={liveTitle} onChangeText={setLiveTitle} style={styles.input} placeholderTextColor={colors.textSecondary} />
          <View style={styles.inlineButtons}>
            {(['public', 'unlisted', 'private'] as const).map((privacy) => (
              <PrimaryButton
                key={privacy}
                label={privacy.toUpperCase()}
                onPress={() => setLivePrivacy(privacy)}
                tone={livePrivacy === privacy ? 'success' : 'primary'}
              />
            ))}
          </View>
          <PrimaryButton
            label={isLive ? 'Encerrar LIVE' : 'Iniciar LIVE'}
            onPress={() => (isLive ? stopLive() : startLive({ title: liveTitle, privacy: livePrivacy }))}
            tone={isLive ? 'danger' : 'primary'}
          />
        </View>
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  safeArea: {
    flex: 1,
    backgroundColor: colors.background
  },
  content: {
    padding: 16,
    gap: 12
  },
  title: {
    color: colors.textPrimary,
    fontSize: 22,
    fontWeight: '800'
  },
  subtitle: {
    color: colors.textSecondary,
    marginBottom: 4
  },
  panel: {
    backgroundColor: colors.panel,
    borderRadius: 14,
    padding: 14,
    gap: 10
  },
  panelLabel: {
    color: colors.textPrimary,
    fontWeight: '700',
    fontSize: 16
  },
  status: {
    color: colors.textSecondary
  },
  timer: {
    color: colors.warning,
    fontWeight: '800',
    fontSize: 28
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
  },
  inlineButtons: {
    gap: 8
  }
});
