import { Pressable, StyleSheet, Text } from 'react-native';
import { colors } from '../theme/colors';

interface PrimaryButtonProps {
  label: string;
  onPress: () => void;
  disabled?: boolean;
  tone?: 'primary' | 'success' | 'danger';
}

const toneColor = {
  primary: colors.accent,
  success: colors.success,
  danger: colors.danger
};

export function PrimaryButton({ label, onPress, disabled, tone = 'primary' }: PrimaryButtonProps) {
  return (
    <Pressable
      style={({ pressed }) => [
        styles.button,
        { backgroundColor: toneColor[tone], opacity: disabled ? 0.45 : pressed ? 0.85 : 1 }
      ]}
      onPress={onPress}
      disabled={disabled}
    >
      <Text style={styles.label}>{label}</Text>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  button: {
    paddingVertical: 12,
    borderRadius: 12,
    alignItems: 'center'
  },
  label: {
    color: colors.textPrimary,
    fontSize: 15,
    fontWeight: '700'
  }
});
