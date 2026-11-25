import AsyncStorage from '@amazon-devices/react-native-async-storage__async-storage';
import {sha256} from './sha256';
export {isAdult} from './adult';

/**
 * Control parental de categorias para adultos (equivalente a Parental.java).
 * Se guarda solo el hash con sal; el desbloqueo dura hasta que la app pasa a segundo plano.
 */
export const Parental = {unlocked: false};
const KEY = 'bluenet_parental';
export const MIN_PIN = 4;

interface Stored {
  salt: string;
  hash: string;
}

async function read(): Promise<Stored | null> {
  try {
    const raw = await AsyncStorage.getItem(KEY);
    return raw ? (JSON.parse(raw) as Stored) : null;
  } catch {
    return null;
  }
}

export async function hasPin(): Promise<boolean> {
  return (await read()) !== null;
}

export function hashPin(salt: string, pin: string): string {
  return sha256(salt + pin);
}

export async function savePin(pin: string): Promise<void> {
  let salt = '';
  for (let i = 0; i < 16; i++) {
    salt += Math.floor(Math.random() * 256).toString(16).padStart(2, '0');
  }
  await AsyncStorage.setItem(KEY, JSON.stringify({salt, hash: hashPin(salt, pin)}));
}

export async function checkPin(pin: string): Promise<boolean> {
  const s = await read();
  return !!s && !!pin && hashPin(s.salt, pin) === s.hash;
}
