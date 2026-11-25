import React, {useEffect, useRef, useState} from 'react';
import {ActivityIndicator, Image, StyleSheet, Text, View} from 'react-native';
import {Btn, Field} from '../components/ui';
import {APP_VERSION, C, SERVER} from '../lib/theme';
import {ApiError, ErrKind, authenticate} from '../lib/xtream';
import {Session, applyAuth, saveSession} from '../lib/session';
import {describeNetwork, isConnected} from '../lib/net';

interface Props {
  noAuto?: boolean;
  onLoggedIn: () => void;
}

/** Mensaje para el cliente: sin nombres de servidor ni detalles tecnicos. */
async function friendly(e: ApiError): Promise<string> {
  switch (e.kind) {
    case ErrKind.CREDENTIALS:
      return 'Usuario o contraseña incorrectos.';
    case ErrKind.ACCOUNT:
      return 'Tu suscripción no está activa. Comunícate con BLUEnet.';
    case ErrKind.NETWORK:
      if (!(await isConnected())) {
        return 'Tu equipo no está conectado a ninguna red. Revisa el Wi-Fi o el cable de internet.';
      }
      return 'Contraseña incorrecta o error del servicio. Revisa tus datos e inténtalo de nuevo.';
    default:
      return 'Contraseña incorrecta o error del servicio. Revisa tus datos e inténtalo de nuevo.';
  }
}

export function LoginScreen({noAuto, onLoggedIn}: Props) {
  const [user, setUser] = useState(Session.user);
  const [pass, setPass] = useState(Session.pass);
  const [showPass, setShowPass] = useState(false);
  const [busy, setBusy] = useState(false);
  const [status, setStatus] = useState('');
  const [isError, setIsError] = useState(false);
  const [net, setNet] = useState('');
  const busyRef = useRef(false);

  const attempt = async (u: string, p: string) => {
    if (busyRef.current) {
      return;
    }
    u = u.trim();
    if (!u || !p) {
      setIsError(true);
      setStatus('Escribe usuario y contraseña.');
      return;
    }
    busyRef.current = true;
    setBusy(true);
    setIsError(false);
    setStatus('Conectando…');
    try {
      const auth = await authenticate(SERVER, u, p, Session.base);
      Session.user = u;
      Session.pass = p;
      applyAuth(auth);
      await saveSession();
      onLoggedIn();
    } catch (e: any) {
      const err = e instanceof ApiError ? e : new ApiError(ErrKind.NETWORK, String(e));
      if (err.kind === ErrKind.CREDENTIALS) {
        setPass('');
      }
      setIsError(true);
      setStatus((await friendly(err)) + (Session.devMode ? `\n(${err.message})` : ''));
    } finally {
      busyRef.current = false;
      setBusy(false);
    }
  };

  useEffect(() => {
    if (!noAuto && Session.user && Session.pass) {
      attempt(Session.user, Session.pass); // sesion guardada: entra directo
    }
    let alive = true;
    const tick = async () => {
      const d = await describeNetwork();
      if (alive) {
        setNet(d);
      }
    };
    tick();
    const t = setInterval(tick, 5000);
    return () => {
      alive = false;
      clearInterval(t);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    <View style={s.root}>
      <View style={s.col}>
        <Image source={require('../assets/logo.png')} style={s.logo} resizeMode="contain" />
        <Text style={s.title}>Iniciar sesión</Text>
        <Field value={user} onChangeText={setUser} placeholder="Usuario" style={s.gap} hasTVPreferredFocus={!Session.user || !!noAuto} />
        <Field
          value={pass}
          onChangeText={setPass}
          placeholder="Contraseña"
          secureTextEntry={!showPass}
          onSubmitEditing={() => attempt(user, pass)}
          style={s.gapSmall}
        />
        <View style={s.right}>
          <Btn small label={showPass ? 'Ocultar contraseña' : 'Mostrar contraseña'} onPress={() => setShowPass(!showPass)} />
        </View>
        <Btn label="Ingresar" primary disabled={busy} onPress={() => attempt(user, pass)} style={s.gap} />
        {busy ? <ActivityIndicator color={C.accentLight} size="large" /> : null}
        <Text style={[s.status, isError && s.error]}>{status}</Text>
      </View>
      <Text style={s.net}>
        {net}
        {'   ·   BLUEnet TV '}
        {APP_VERSION}
      </Text>
    </View>
  );
}

const s = StyleSheet.create({
  root: {flex: 1, backgroundColor: C.bg, alignItems: 'center', justifyContent: 'center'},
  col: {width: 640, alignItems: 'stretch'},
  logo: {width: 220, height: 220, alignSelf: 'center'},
  title: {color: C.text, fontSize: 36, textAlign: 'center', marginTop: 16, marginBottom: 24},
  gap: {marginBottom: 18},
  gapSmall: {marginBottom: 10},
  right: {alignItems: 'flex-end', marginBottom: 18},
  status: {color: C.muted, fontSize: 22, textAlign: 'center', marginTop: 12},
  error: {color: C.error},
  net: {position: 'absolute', bottom: 24, color: C.muted, fontSize: 18},
});
