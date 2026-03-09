import { Pressable, StyleSheet, Text, ViewStyle } from 'react-native';
import { colors } from '../theme/colors';

interface PrimaryButtonProps {
  label: string;
  onPress: () => void;
  disabled?: boolean;
  tone?: 'primary' | 'success' | 'danger';
  fill?: boolean;
}

const toneColor = {
  primary: colors.accent,
  success: colors.success,
  danger: colors.danger
};

export function PrimaryButton({ label, onPress, disabled, tone = 'primary', fill = true }: PrimaryButtonProps) {
  return (
    <Pressable
      style={({ pressed }) => [
        styles.button,
        fill ? (styles.fill as ViewStyle) : null,
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
    minHeight: 44,
    paddingHorizontal: 10,
    paddingVertical: 10,
    borderRadius: 10,
    alignItems: 'center',
    justifyContent: 'center'
  },
  fill: {
    flex: 1
  },
  label: {
    color: colors.textPrimary,
    fontSize: 13,
    fontWeight: '700'
  }
});
