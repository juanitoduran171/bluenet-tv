import AsyncStorage from '@amazon-devices/react-native-async-storage__async-storage';
import {Auth} from './xtream';

/** Datos de sesion en memoria + persistencia (equivalente a Session.java). */
export const Session = {
  base: null as string | null,
  user: '',
  pass: '',
  expTs: 0,
  maxConnections: 0,
  devMode: false,
  lastChanId: '',
  lastChanCat: '',
};

const KEY = 'bluenet_session';

export function isLoggedIn(): boolean {
  return !!Session.base && Session.user.length > 0 && Session.pass.length > 0;
}

export function applyAuth(a: Auth) {
  Session.base = a.base;
  Session.expTs = a.expTs;
  Session.maxConnections = a.maxConnections;
}

export async function loadSession(): Promise<void> {
  try {
    const raw = await AsyncStorage.getItem(KEY);
    if (raw) {
      Object.assign(Session, JSON.parse(raw));
    }
  } catch {
    // sin datos guardados
  }
}

export async function saveSession(): Promise<void> {
  try {
    await AsyncStorage.setItem(KEY, JSON.stringify(Session));
  } catch {
    // el almacenamiento no es critico
  }
}

/** Cerrar sesion: olvida clave, servidor verificado y ultimo canal; conserva el usuario. */
export async function logout(): Promise<void> {
  Session.pass = '';
  Session.base = null;
  Session.expTs = 0;
  Session.lastChanId = '';
  Session.lastChanCat = '';
  await saveSession();
}

export async function saveLastChannel(id: string, categoryId: string): Promise<void> {
  const cat = categoryId || '*';
  if (id === Session.lastChanId && cat === Session.lastChanCat) {
    return;
  }
  Session.lastChanId = id;
  Session.lastChanCat = cat;
  await saveSession();
}
