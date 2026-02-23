import { useState } from 'react';
import { Modal, Pressable, StyleSheet, Switch, Text, View } from 'react-native';
import { CameraView } from 'expo-camera';
import { useReplayController } from '../state/useReplayController';
import { PrimaryButton } from '../components/PrimaryButton';
import { colors } from '../theme/colors';
import { formatClock } from '../utils/time';

export function ReplayScreen() {
  const [menuOpen, setMenuOpen] = useState(false);

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
    toggleDriveLink
  } = useReplayController();

  return (
    <View style={styles.root}>
      <CameraView style={styles.preview} facing="back" mute={false} zoom={Math.max(0, Math.min(1, (selectedZoomRatio - 1) / 9))} ref={(ref) => bindRecorder(ref as never)} />

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
          <PrimaryButton label="Salvar replay (20s)" onPress={saveReplay} disabled={!isRecording} tone="primary" />
        </View>
      </View>

      <Modal visible={menuOpen} transparent animationType="fade" onRequestClose={() => setMenuOpen(false)}>
        <View style={styles.modalOverlay}>
          <View style={styles.modalCard}>
            <Text style={styles.modalTitle}>Configurar upload automático</Text>
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

            <PrimaryButton label="Fechar" onPress={() => setMenuOpen(false)} tone="danger" />
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
    padding: 16,
    gap: 12
  },
  modalTitle: {
    color: colors.textPrimary,
    fontSize: 18,
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
  }
});
