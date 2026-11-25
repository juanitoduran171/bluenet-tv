import React, {useState} from 'react';
import {Pressable, StyleProp, StyleSheet, Text, TextInput, TextInputProps, TextStyle, ViewStyle} from 'react-native';
import {C} from '../lib/theme';

interface BtnProps {
  label: string;
  onPress?: () => void;
  onFocus?: () => void;
  primary?: boolean;
  selected?: boolean;
  small?: boolean;
  disabled?: boolean;
  multiline?: boolean;
  hasTVPreferredFocus?: boolean;
  style?: StyleProp<ViewStyle>;
  textStyle?: StyleProp<TextStyle>;
}

/** Boton enfocable con el control remoto (resaltado al tener foco). */
export function Btn(p: BtnProps) {
  const [focused, setFocused] = useState(false);
  return (
    <Pressable
      onPress={p.disabled ? undefined : p.onPress}
      onFocus={() => {
        setFocused(true);
        p.onFocus && p.onFocus();
      }}
      onBlur={() => setFocused(false)}
      hasTVPreferredFocus={p.hasTVPreferredFocus}
      style={[
        s.btn,
        p.small && s.small,
        p.primary && s.primary,
        p.selected && s.selected,
        focused && s.focused,
        p.disabled && s.disabled,
        p.style,
      ]}>
      <Text style={[s.btnText, p.small && s.smallText, p.textStyle]} numberOfLines={p.multiline ? 4 : 1}>
        {p.label}
      </Text>
    </Pressable>
  );
}

/** Campo de texto con borde resaltado al tener foco. */
export function Field(props: TextInputProps & {big?: boolean}) {
  const [focused, setFocused] = useState(false);
  const {style, big, onFocus, onBlur, ...rest} = props;
  return (
    <TextInput
      placeholderTextColor={C.muted}
      autoCapitalize="none"
      autoCorrect={false}
      {...rest}
      onFocus={e => {
        setFocused(true);
        onFocus && onFocus(e);
      }}
      onBlur={e => {
        setFocused(false);
        onBlur && onBlur(e);
      }}
      style={[s.field, big && s.fieldBig, focused && s.fieldFocused, style]}
    />
  );
}

const s = StyleSheet.create({
  btn: {
    backgroundColor: C.panel,
    borderRadius: 10,
    paddingHorizontal: 22,
    paddingVertical: 10,
    borderWidth: 2,
    borderColor: 'transparent',
    alignItems: 'center',
    justifyContent: 'center',
  },
  small: {paddingHorizontal: 16, paddingVertical: 8},
  primary: {backgroundColor: C.accent},
  selected: {backgroundColor: C.accent},
  focused: {backgroundColor: C.accentLight, borderColor: C.text},
  disabled: {opacity: 0.5},
  btnText: {color: C.text, fontSize: 22},
  smallText: {fontSize: 19},
  field: {
    backgroundColor: C.panel,
    color: C.text,
    borderRadius: 10,
    borderWidth: 2,
    borderColor: '#2A4258',
    paddingHorizontal: 18,
    fontSize: 22,
    height: 60,
  },
  fieldBig: {height: 70, fontSize: 26},
  fieldFocused: {borderColor: C.accentLight},
});
