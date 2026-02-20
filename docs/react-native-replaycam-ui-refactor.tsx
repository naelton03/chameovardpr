import React, {useMemo, useState} from 'react';
import {
  SafeAreaView,
  StyleSheet,
  Text,
  TouchableOpacity,
  View,
  Image,
  StatusBar,
} from 'react-native';
import {Camera, CameraType} from 'expo-camera';
import {
  Aperture,
  Gauge,
  Monitor,
  Settings2,
  Images,
  CircleDot,
  Zap,
  ChevronUp,
  Scan,
  Repeat,
} from 'lucide-react-native';

const MODES = ['VLOG', 'VÍDEO', 'REPLAY'] as const;

type Mode = (typeof MODES)[number];

export default function ReplayCamScreen() {
  const [cameraType, setCameraType] = useState<CameraType>('back');
  const [isRecording, setIsRecording] = useState(false);
  const [selectedMode, setSelectedMode] = useState<Mode>('REPLAY');

  const shutterInnerStyle = useMemo(
    () => [styles.shutterInner, isRecording && styles.shutterInnerRecording],
    [isRecording],
  );

  const onMainActionPress = () => {
    // TODO: conectar com sua engine de captura/replay
    setIsRecording(prev => !prev);
  };

  return (
    <SafeAreaView style={styles.screen}>
      <StatusBar translucent backgroundColor="transparent" barStyle="light-content" />

      {/* Fullscreen Camera Preview */}
      <Camera style={styles.cameraPreview} type={cameraType} ratio="16:9" />

      {/* Top status bar (glassmorphism) */}
      <View style={styles.topGlassBar}>
        <Scan size={18} color="#FFFFFF" style={styles.topIcon} />
        <Zap size={18} color="#FFFFFF" style={styles.topIcon} />
        <ChevronUp size={18} color="#FFFFFF" style={styles.topIcon} />
        <Gauge size={18} color="#FFFFFF" style={styles.topIcon} />
        <Monitor size={18} color="#FFFFFF" style={styles.topIcon} />
      </View>

      {/* Bottom overlay controls */}
      <View style={styles.bottomOverlay}>
        {/* Mode selector */}
        <View style={styles.modeSelectorRow}>
          {MODES.map(mode => {
            const selected = mode === selectedMode;
            return (
              <TouchableOpacity
                key={mode}
                activeOpacity={0.85}
                style={styles.modeButton}
                onPress={() => setSelectedMode(mode)}>
                <Text style={[styles.modeText, selected && styles.modeTextSelected]}>{mode}</Text>
              </TouchableOpacity>
            );
          })}
        </View>

        {/* Main action row */}
        <View style={styles.actionRow}>
          {/* Left secondary button */}
          <TouchableOpacity activeOpacity={0.85} style={styles.floatingSideButton}>
            <Images size={22} color="#FFFFFF" />
            <Image
              source={{uri: 'https://picsum.photos/64'}}
              style={styles.galleryPreviewThumb}
            />
          </TouchableOpacity>

          {/* Main shutter */}
          <TouchableOpacity
            onPress={onMainActionPress}
            activeOpacity={0.9}
            style={styles.shutterOuter}>
            <View style={shutterInnerStyle} />
          </TouchableOpacity>

          {/* Right secondary button */}
          <TouchableOpacity
            activeOpacity={0.85}
            style={styles.floatingSideButton}
            onPress={() => setCameraType(prev => (prev === 'back' ? 'front' : 'back'))}>
            <Repeat size={22} color="#FFFFFF" />
          </TouchableOpacity>
        </View>

        {/* Optional quick action chip */}
        <View style={styles.quickChip}>
          <Aperture size={14} color="#24D16D" />
          <Text style={styles.quickChipText}>1x</Text>
          <Text style={styles.quickChipDivider}>|</Text>
          <Text style={styles.quickChipTextMuted}>2x</Text>
        </View>

        {/* Floating settings icon */}
        <TouchableOpacity style={styles.floatingSettings} activeOpacity={0.85}>
          <Settings2 size={20} color="#24D16D" />
        </TouchableOpacity>
      </View>
    </SafeAreaView>
  );
}

const SHADOW_WHITE = {
  shadowColor: '#000',
  shadowOffset: {width: 0, height: 1},
  shadowOpacity: 0.45,
  shadowRadius: 2,
  elevation: 2,
};

const styles = StyleSheet.create({
  screen: {
    flex: 1,
    backgroundColor: '#000',
  },
  cameraPreview: {
    ...StyleSheet.absoluteFillObject,
  },

  // Top glassmorphism status bar
  topGlassBar: {
    position: 'absolute',
    top: 14,
    left: 16,
    right: 16,
    height: 42,
    borderRadius: 14,
    backgroundColor: 'rgba(0,0,0,0.40)',
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.10)',
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-around',
    paddingHorizontal: 12,
  },
  topIcon: {
    ...SHADOW_WHITE,
  },

  // Bottom controls
  bottomOverlay: {
    position: 'absolute',
    left: 0,
    right: 0,
    bottom: 24,
    alignItems: 'center',
  },
  modeSelectorRow: {
    position: 'absolute',
    bottom: 142,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 18,
    paddingHorizontal: 16,
    paddingVertical: 6,
    borderRadius: 20,
    backgroundColor: 'rgba(0,0,0,0.18)',
  },
  modeButton: {
    paddingHorizontal: 2,
  },
  modeText: {
    fontSize: 24,
    color: 'rgba(255,255,255,0.65)',
    fontWeight: '600',
    letterSpacing: 0.4,
    ...SHADOW_WHITE,
  },
  modeTextSelected: {
    color: '#24D16D',
    fontWeight: '700',
  },

  actionRow: {
    width: '100%',
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-evenly',
    paddingHorizontal: 24,
  },
  floatingSideButton: {
    width: 58,
    height: 58,
    borderRadius: 29,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: 'rgba(80,80,80,0.42)',
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.16)',
  },
  galleryPreviewThumb: {
    position: 'absolute',
    width: 48,
    height: 48,
    borderRadius: 12,
    opacity: 0.3,
  },

  shutterOuter: {
    width: 94,
    height: 94,
    borderRadius: 47,
    borderWidth: 4,
    borderColor: '#FFFFFF',
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: 'rgba(0,0,0,0.10)',
  },
  shutterInner: {
    width: 68,
    height: 68,
    borderRadius: 34,
    backgroundColor: '#FFFFFF',
  },
  shutterInnerRecording: {
    backgroundColor: '#FF2D2D',
  },

  quickChip: {
    position: 'absolute',
    bottom: 228,
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 14,
    paddingVertical: 8,
    borderRadius: 18,
    backgroundColor: 'rgba(0,0,0,0.35)',
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.12)',
  },
  quickChipText: {
    marginLeft: 6,
    color: '#24D16D',
    fontWeight: '700',
    fontSize: 20,
  },
  quickChipDivider: {
    marginHorizontal: 8,
    color: 'rgba(255,255,255,0.4)',
  },
  quickChipTextMuted: {
    color: '#FFFFFF',
    fontWeight: '600',
    fontSize: 20,
  },

  floatingSettings: {
    position: 'absolute',
    right: 24,
    bottom: 88,
    width: 42,
    height: 42,
    borderRadius: 21,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: 'rgba(0,0,0,0.28)',
  },
});

/*
Dependências sugeridas:
- lucide-react-native
- expo-camera (ou react-native-vision-camera)

Se não usar Expo, substitua <Camera /> pela sua implementação de preview atual.
*/
