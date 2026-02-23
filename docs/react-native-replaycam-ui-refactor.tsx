import React, {useMemo, useState} from 'react';
import {StatusBar, Image} from 'react-native';
import {Camera, CameraType} from 'expo-camera';
import {
  Button,
  Circle,
  Paragraph,
  Separator,
  SizableText,
  Stack,
  XStack,
  YStack,
} from 'tamagui';
import {
  Aperture,
  Gauge,
  Images,
  Monitor,
  Repeat,
  Scan,
  Settings2,
  Sparkles,
  Zap,
} from '@tamagui/lucide-icons';

const MODES = ['VLOG', 'VÍDEO', 'REPLAY'] as const;
type Mode = (typeof MODES)[number];

export default function ReplayCamScreen() {
  const [cameraType, setCameraType] = useState<CameraType>('back');
  const [isRecording, setIsRecording] = useState(false);
  const [selectedMode, setSelectedMode] = useState<Mode>('REPLAY');

  const actionLabel = useMemo(() => {
    if (selectedMode === 'REPLAY') return isRecording ? 'GRAVANDO BUFFER' : 'PRONTO PARA REPLAY';
    return isRecording ? 'GRAVANDO' : 'PRONTO';
  }, [isRecording, selectedMode]);

  return (
    <YStack f={1} bg="$color1" pos="relative">
      <StatusBar translucent backgroundColor="transparent" barStyle="light-content" />

      <Camera
        style={{position: 'absolute', top: 0, right: 0, bottom: 0, left: 0}}
        type={cameraType}
        ratio="16:9"
      />

      <YStack f={1} px="$4" pt="$5" pb="$4" jc="space-between">
        <XStack
          ai="center"
          jc="space-between"
          px="$3"
          py="$2"
          bg="rgba(10, 14, 20, 0.56)"
          borderWidth={1}
          borderColor="rgba(255,255,255,0.12)"
          br="$6">
          <XStack gap="$3" ai="center">
            <Scan size={16} color="#E9EEF5" />
            <Zap size={16} color="#E9EEF5" />
            <Gauge size={16} color="#E9EEF5" />
            <Monitor size={16} color="#E9EEF5" />
          </XStack>

          <XStack ai="center" gap="$2">
            <Circle size={8} bg={isRecording ? '$red10' : '$green10'} />
            <SizableText size="$2" color="$color12" fontWeight="700" letterSpacing={0.8}>
              {actionLabel}
            </SizableText>
          </XStack>
        </XStack>

        <YStack gap="$4">
          <XStack ai="center" jc="center" gap="$2">
            {MODES.map(mode => {
              const selected = mode === selectedMode;
              return (
                <Button
                  key={mode}
                  size="$3"
                  chromeless
                  px="$2"
                  py="$1"
                  onPress={() => setSelectedMode(mode)}>
                  <SizableText
                    size="$7"
                    color={selected ? '$green10' : 'rgba(255,255,255,0.62)'}
                    fontWeight={selected ? '800' : '600'}
                    letterSpacing={0.6}>
                    {mode}
                  </SizableText>
                </Button>
              );
            })}
          </XStack>

          <XStack ai="center" jc="center" gap="$6">
            <Stack ai="center" gap="$2">
              <Button
                circular
                size="$6"
                bg="rgba(24,26,33,0.72)"
                borderWidth={1}
                borderColor="rgba(255,255,255,0.18)">
                <Images size={20} color="#FFFFFF" />
                <Image
                  source={{uri: 'https://picsum.photos/64'}}
                  style={{position: 'absolute', width: 46, height: 46, borderRadius: 11, opacity: 0.33}}
                />
              </Button>
              <Paragraph size="$2" color="rgba(255,255,255,0.68)">
                Galeria
              </Paragraph>
            </Stack>

            <Button
              circular
              size="$9"
              bg="rgba(255,255,255,0.14)"
              borderWidth={4}
              borderColor="$color12"
              onPress={() => setIsRecording(prev => !prev)}>
              <Circle size={68} bg={isRecording ? '$red10' : '$color12'} />
            </Button>

            <Stack ai="center" gap="$2">
              <Button
                circular
                size="$6"
                bg="rgba(24,26,33,0.72)"
                borderWidth={1}
                borderColor="rgba(255,255,255,0.18)"
                onPress={() => setCameraType(prev => (prev === 'back' ? 'front' : 'back'))}>
                <Repeat size={20} color="#FFFFFF" />
              </Button>
              <Paragraph size="$2" color="rgba(255,255,255,0.68)">
                Câmera
              </Paragraph>
            </Stack>
          </XStack>

          <XStack ai="center" jc="space-between">
            <XStack
              ai="center"
              gap="$2"
              px="$3"
              py="$2"
              bg="rgba(10, 14, 20, 0.60)"
              borderWidth={1}
              borderColor="rgba(255,255,255,0.12)"
              br="$10">
              <Aperture size={14} color="#34D399" />
              <SizableText size="$5" color="$green10" fontWeight="800">
                1x
              </SizableText>
              <Separator vertical borderColor="rgba(255,255,255,0.25)" />
              <SizableText size="$5" color="$color11" fontWeight="600">
                2x
              </SizableText>
            </XStack>

            <Button
              circular
              size="$4"
              bg="rgba(10, 14, 20, 0.60)"
              borderWidth={1}
              borderColor="rgba(255,255,255,0.12)">
              <Settings2 size={16} color="#34D399" />
            </Button>
          </XStack>

          <XStack
            ai="center"
            jc="space-between"
            px="$3"
            py="$2"
            bg="rgba(10, 14, 20, 0.56)"
            borderWidth={1}
            borderColor="rgba(255,255,255,0.12)"
            br="$6">
            <XStack ai="center" gap="$2">
              <Sparkles size={14} color="#60A5FA" />
              <SizableText size="$2" color="$color12" fontWeight="700">
                Auto composição ativa
              </SizableText>
            </XStack>
            <SizableText size="$2" color="$color11">
              20s buffer
            </SizableText>
          </XStack>
        </YStack>
      </YStack>
    </YStack>
  );
}

/*
Dependências sugeridas:
- tamagui
- @tamagui/lucide-icons
- expo-camera (ou react-native-vision-camera)

Observação:
- Este arquivo representa um refactor visual da UI com Tamagui.
- Para produção, configure TamaguiProvider + tokens/themes no app root.
*/
