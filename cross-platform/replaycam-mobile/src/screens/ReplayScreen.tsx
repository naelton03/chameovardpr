import { useState } from 'react';
import { Modal, Pressable, ScrollView, StyleSheet, Switch, Text, View } from 'react-native';
import { CameraView, useCameraPermissions } from 'expo-camera';
import { useReplayController } from '../state/useReplayController';
import { PrimaryButton } from '../components/PrimaryButton';
import { colors } from '../theme/colors';
import { formatClock } from '../utils/time';
import type { ReplayPlayType } from '../types/replay';

const PLAY_TYPE_OPTIONS: ReplayPlayType[] = ['GOL', 'DEFESA', 'LANCE'];

export function ReplayScreen() {
  const [menuOpen, setMenuOpen] = useState(false);
  const [saveTypeModalOpen, setSaveTypeModalOpen] = useState(false);
  const [selectedPlayType, setSelectedPlayType] = useState<ReplayPlayType | null>(null);
  const [cameraPermission, requestCameraPermission] = useCameraPermissions();

  const {
    isRecording,
    elapsedSeconds,
    status,
    selectedZoomRatio,
    bindRecorder,
    startRecording,
    saveReplay,
    stopAndSaveSession,
    lastOutputPath,
    autoUploadEnabled,
    driveEmail,
    toggleAutoUpload,
    replayDurationSec,
    updateReplayDurationSec,
    toggleDriveLink,
    isSavingReplay,
    supportedFpsOptions,
    supportedQualityOptions,
    recordingPreference,
    updateRecordingFps,
    updateRecordingQuality
  } = useReplayController();

  const openSaveTypeModal = () => {
    setSelectedPlayType(null);
    setSaveTypeModalOpen(true);
  };

  const confirmSaveReplay = async () => {
    if (!selectedPlayType || isSavingReplay) return;
    await saveReplay(selectedPlayType);
    setSaveTypeModalOpen(false);
    setSelectedPlayType(null);
  };

  return (
    <View style={styles.root}>
      {cameraPermission?.granted ? (
        <CameraView style={styles.preview} facing="back" mute={false} zoom={Math.max(0, Math.min(1, (selectedZoomRatio - 1) / 9))} ref={(ref) => bindRecorder(ref as never)} />
      ) : (
        <View style={styles.permissionFallback}>
          <Text style={styles.permissionTitle}>Permissão de câmera necessária</Text>
          <Text style={styles.permissionText}>Autorize a câmera para iniciar o preview e as gravações.</Text>
          <PrimaryButton label="Permitir câmera" onPress={requestCameraPermission} tone="primary" />
        </View>
      )}

      <Pressable style={styles.menuButton} onPress={() => setMenuOpen(true)}>
        <Text style={styles.menuButtonText}>☰</Text>
      </Pressable>

      <View style={styles.topInfoContainer}>
        <Text style={styles.brand}>ReplayCam</Text>
        <Text style={styles.timer}>{formatClock(elapsedSeconds)}</Text>
        <Text style={[styles.status, status.isError ? styles.statusError : null]}>{status.message}</Text>
        {lastOutputPath ? <Text style={styles.pathText}>{lastOutputPath}</Text> : null}
      </View>

      <View style={styles.bottomPanel}>
        <View style={styles.row}>
          <PrimaryButton
            label={isRecording ? 'Parar' : 'Gravar'}
            onPress={isRecording ? stopAndSaveSession : startRecording}
            tone={isRecording ? 'danger' : 'success'}
          />
          <PrimaryButton label={isSavingReplay ? 'Salvando replay...' : `Salvar replay (${replayDurationSec}s)`} onPress={openSaveTypeModal} disabled={!isRecording || isSavingReplay} tone="primary" />
        </View>
      </View>

      <Modal visible={menuOpen} transparent animationType="fade" onRequestClose={() => setMenuOpen(false)}>
        <View style={styles.modalOverlay}>
          <View style={styles.modalCard}>
            <ScrollView
              style={styles.modalScroll}
              contentContainerStyle={styles.modalContent}
              showsVerticalScrollIndicator={false}
            >
            <Text style={styles.modalTitle}>Configurações</Text>
            <Text style={styles.modalSectionTitle}>Upload automático</Text>
            <Text style={styles.modalText}>
              {driveEmail ? `Conta vinculada: ${driveEmail}` : 'Nenhuma conta vinculada'}
            </Text>

            <PrimaryButton
              label={driveEmail ? 'Desvincular conta' : 'Vincular conta Google'}
              onPress={toggleDriveLink}
              tone="primary"
            />

            <View style={styles.switchRow}>
              <Text style={styles.modalText}>Realizar uploads automáticos?</Text>
              <Switch value={autoUploadEnabled} onValueChange={toggleAutoUpload} />
            </View>


            <Text style={styles.modalSectionTitle}>FPS</Text>
            <View style={styles.durationWrap}>
              {supportedFpsOptions.map((fps) => (
                <PrimaryButton
                  key={`fps-${fps}`}
                  label={`${fps}`}
                  onPress={() => updateRecordingFps(fps)}
                  tone={recordingPreference.fps === fps ? 'success' : 'primary'}
                  fill={false}
                />
              ))}
            </View>

            <Text style={styles.modalSectionTitle}>Qualidade</Text>
            <View style={styles.durationWrap}>
              {supportedQualityOptions.map((quality) => (
                <PrimaryButton
                  key={`quality-${quality}`}
                  label={quality}
                  onPress={() => updateRecordingQuality(quality)}
                  tone={recordingPreference.quality === quality ? 'success' : 'primary'}
                  fill={false}
                />
              ))}
            </View>

            <Text style={styles.modalSectionTitle}>Duração do replay</Text>
            <View style={styles.durationWrap}>
              {[10, 15, 20, 25, 30, 35, 40].map((seconds) => (
                <PrimaryButton
                  key={`replay-${seconds}`}
                  label={`${seconds}s`}
                  onPress={() => updateReplayDurationSec(seconds)}
                  tone={replayDurationSec === seconds ? 'success' : 'primary'}
                  fill={false}
                />
              ))}
            </View>

            <PrimaryButton label="Fechar" onPress={() => setMenuOpen(false)} tone="danger" />
            </ScrollView>
          </View>
        </View>
      </Modal>

      <Modal visible={saveTypeModalOpen} transparent animationType="fade" onRequestClose={() => {}}>
        <View style={styles.modalOverlay}>
          <View style={styles.saveTypeModalCard}>
            <Text style={styles.modalTitle}>O que foi este lance ?</Text>
            <View style={styles.playTypeOptionsWrap}>
              {PLAY_TYPE_OPTIONS.map((option) => (
                <Pressable
                  key={option}
                  onPress={() => setSelectedPlayType(option)}
                  style={[
                    styles.playTypeOption,
                    selectedPlayType === option ? styles.playTypeOptionSelected : null
                  ]}
                >
                  <Text style={styles.playTypeOptionLabel}>{option}</Text>
                </Pressable>
              ))}
            </View>
            <View style={styles.saveButtonWrap}>
              <PrimaryButton
                label={isSavingReplay ? 'Salvando...' : 'Salvar'}
                onPress={confirmSaveReplay}
                disabled={!selectedPlayType || isSavingReplay}
                tone="success"
              />
            </View>
          </View>
        </View>
      </Modal>
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
  menuButton: {
    position: 'absolute',
    top: 56,
    right: 16,
    width: 40,
    height: 40,
    borderRadius: 20,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: 'rgba(16, 25, 41, 0.92)'
  },
  menuButtonText: {
    color: colors.textPrimary,
    fontSize: 20,
    fontWeight: '700'
  },
  topInfoContainer: {
    marginTop: 56,
    marginHorizontal: 16,
    marginRight: 64,
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
  pathText: {
    color: colors.textSecondary,
    fontSize: 11
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
  row: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 8
  },
  modalOverlay: {
    flex: 1,
    backgroundColor: 'rgba(0,0,0,0.5)',
    justifyContent: 'center',
    padding: 20
  },
  modalCard: {
    backgroundColor: '#0f1b31',
    borderRadius: 12,
    paddingHorizontal: 16,
    paddingVertical: 12,
    maxHeight: '85%'
  },
  saveTypeModalCard: {
    backgroundColor: '#0f1b31',
    borderRadius: 12,
    paddingHorizontal: 16,
    paddingVertical: 14,
    gap: 14
  },
  modalScroll: {
    flexGrow: 0
  },
  modalContent: {
    gap: 12,
    paddingBottom: 4
  },
  modalTitle: {
    color: colors.textPrimary,
    fontSize: 18,
    fontWeight: '700'
  },
  modalSectionTitle: {
    color: colors.textPrimary,
    fontSize: 15,
    fontWeight: '700'
  },
  modalText: {
    color: colors.textSecondary,
    fontSize: 14
  },
  switchRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center'
  },
  durationWrap: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 8
  },
  playTypeOptionsWrap: {
    gap: 8
  },
  playTypeOption: {
    borderRadius: 10,
    borderWidth: 1,
    borderColor: 'rgba(149, 165, 184, 0.45)',
    paddingVertical: 12,
    paddingHorizontal: 14,
    backgroundColor: 'rgba(20, 31, 48, 0.82)'
  },
  playTypeOptionSelected: {
    borderColor: colors.success,
    backgroundColor: 'rgba(34, 197, 94, 0.2)'
  },
  playTypeOptionLabel: {
    color: colors.textPrimary,
    fontSize: 16,
    fontWeight: '700'
  },
  saveButtonWrap: {
    alignItems: 'flex-end'
  },
  permissionFallback: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: 24,
    gap: 12,
    backgroundColor: '#000'
  },
  permissionTitle: {
    color: colors.textPrimary,
    fontSize: 18,
    fontWeight: '700',
    textAlign: 'center'
  },
  permissionText: {
    color: colors.textSecondary,
    fontSize: 14,
    textAlign: 'center'
  },
});
