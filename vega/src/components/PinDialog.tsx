import React, {useState} from 'react';
import {StyleSheet, Text, View} from 'react-native';
import {Btn, Field} from './ui';
import {C} from '../lib/theme';
import {MIN_PIN, Parental, checkPin, savePin} from '../lib/parental';
import {Session} from '../lib/session';

export type PinMode = 'create' | 'enter' | 'forgot' | 'menu';

interface Props {
  mode: PinMode;
  /** Se desbloqueo / se guardo la contraseña / se bloqueo desde el menu. */
  onDone: () => void;
  onCancel: () => void;
}

/** Dialogo de control parental (ocupa toda la pantalla para que el foco no se escape). */
export function PinDialog({mode: initialMode, onDone, onCancel}: Props) {
  const [mode, setMode] = useState<PinMode>(initialMode);
  const [message, setMessage] = useState<string | null>(null);
  const [a, setA] = useState('');
  const [b, setB] = useState('');
  const [err, setErr] = useState('');
  const [afterEnter, setAfterEnter] = useState<'unlock' | 'change'>('unlock');

  const go = (m: PinMode, msg?: string) => {
    setMode(m);
    setA('');
    setB('');
    setErr('');
    setMessage(msg || null);
  };

  const create = async () => {
    if (a.length < MIN_PIN || !/^\d+$/.test(a)) {
      setErr(`Usa solo números, mínimo ${MIN_PIN}.`);
      return;
    }
    if (a !== b) {
      setB('');
      setErr('Las contraseñas no coinciden.');
      return;
    }
    await savePin(a);
    Parental.unlocked = true;
    onDone();
  };

  const enter = async () => {
    if (await checkPin(a)) {
      if (afterEnter === 'change') {
        go('create', 'Escribe la nueva contraseña de control parental.');
      } else {
        Parental.unlocked = true;
        onDone();
      }
    } else {
      setA('');
      setErr('Contraseña incorrecta.');
    }
  };

  const forgot = () => {
    if (Session.pass && a === Session.pass) {
      go('create', 'Escribe la nueva contraseña de control parental.');
    } else {
      setA('');
      setErr('No coincide con la contraseña de tu cuenta.');
    }
  };

  let body: React.ReactNode;
  if (mode === 'menu') {
    body = (
      <>
        <Text style={st.text}>Control parental — {Parental.unlocked ? 'desbloqueado' : 'bloqueado'}</Text>
        {Parental.unlocked && (
          <Btn
            label="Bloquear ahora"
            hasTVPreferredFocus
            style={st.row}
            onPress={() => {
              Parental.unlocked = false;
              onDone();
            }}
          />
        )}
        <Btn
          label="Cambiar contraseña"
          hasTVPreferredFocus={!Parental.unlocked}
          style={st.row}
          onPress={() => {
            setAfterEnter('change');
            go('enter', 'Primero escribe la contraseña actual.');
          }}
        />
        <Btn label="Cerrar" style={st.row} onPress={onCancel} />
      </>
    );
  } else if (mode === 'create') {
    body = (
      <>
        <Text style={st.text}>
          {message ||
            `Esta categoría es solo para adultos. Crea una contraseña de control parental (mínimo ${MIN_PIN} números). Se pedirá cada vez que se quiera entrar.`}
        </Text>
        <Field value={a} onChangeText={setA} placeholder="Nueva contraseña" keyboardType="numeric" secureTextEntry hasTVPreferredFocus style={st.row} />
        <Field value={b} onChangeText={setB} placeholder="Repite la contraseña" keyboardType="numeric" secureTextEntry style={st.row} onSubmitEditing={create} />
        <View style={st.buttons}>
          <Btn label="Guardar" primary onPress={create} />
          <Btn label="Cancelar" onPress={onCancel} style={st.ml} />
        </View>
      </>
    );
  } else if (mode === 'enter') {
    body = (
      <>
        <Text style={st.text}>{message || 'Contenido protegido. Escribe la contraseña de control parental.'}</Text>
        <Field value={a} onChangeText={setA} placeholder="Contraseña" keyboardType="numeric" secureTextEntry hasTVPreferredFocus style={st.row} onSubmitEditing={enter} />
        <View style={st.buttons}>
          <Btn label="Desbloquear" primary onPress={enter} />
          <Btn label="La olvidé" onPress={() => go('forgot')} style={st.ml} />
          <Btn label="Cancelar" onPress={onCancel} style={st.ml} />
        </View>
      </>
    );
  } else {
    body = (
      <>
        <Text style={st.text}>
          Para crear una nueva contraseña de control parental, escribe la contraseña de tu cuenta BLUEnet TV (la que usas para
          iniciar sesión).
        </Text>
        <Field value={a} onChangeText={setA} placeholder="Contraseña de la cuenta" secureTextEntry hasTVPreferredFocus style={st.row} onSubmitEditing={forgot} />
        <View style={st.buttons}>
          <Btn label="Continuar" primary onPress={forgot} />
          <Btn label="Cancelar" onPress={onCancel} style={st.ml} />
        </View>
      </>
    );
  }

  return (
    <View style={st.overlay}>
      <View style={st.box}>
        <Text style={st.title}>Control parental</Text>
        {body}
        {err ? <Text style={st.err}>{err}</Text> : null}
      </View>
    </View>
  );
}

const st = StyleSheet.create({
  overlay: {flex: 1, backgroundColor: C.bg, alignItems: 'center', justifyContent: 'center'},
  box: {width: 760, backgroundColor: C.panel, borderRadius: 18, padding: 36},
  title: {color: C.text, fontSize: 32, marginBottom: 12},
  text: {color: C.muted, fontSize: 22, marginBottom: 16},
  row: {marginBottom: 14},
  buttons: {flexDirection: 'row', marginTop: 8},
  ml: {marginLeft: 14},
  err: {color: C.error, fontSize: 20, marginTop: 14},
});
