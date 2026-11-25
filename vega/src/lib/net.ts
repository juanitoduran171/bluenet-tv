import {fetch as netFetch, NetInfoStateType} from '@amazon-devices/keplerscript-netmgr-lib';

/** "Red: Wi-Fi «Casa» · señal 72%", "Red: cable (Ethernet)", "Red: ninguna"... para soporte. */
export async function describeNetwork(): Promise<string> {
  try {
    const st: any = await netFetch();
    if (!st || !st.isConnected || st.type === NetInfoStateType.none) {
      return 'Red: ninguna (sin conexión)';
    }
    let s: string;
    if (st.type === NetInfoStateType.ethernet) {
      s = 'Red: cable (Ethernet)';
    } else if (st.type === NetInfoStateType.wifi) {
      const d = st.details || {};
      const ssid = d.ssid && !/unknown ssid|invalid ssid/i.test(d.ssid) ? `«${d.ssid}»` : '(nombre no disponible)';
      s = `Red: Wi-Fi ${ssid}` + (typeof d.strength === 'number' ? ` · señal ${d.strength}%` : '');
    } else if (st.type === NetInfoStateType.cellular) {
      s = 'Red: datos móviles';
    } else {
      s = 'Red: conectada';
    }
    if (st.isInternetReachable === false) {
      s += ' · sin acceso a internet';
    }
    return s;
  } catch {
    return 'Red: desconocida';
  }
}

export async function isConnected(): Promise<boolean> {
  try {
    const st: any = await netFetch();
    return !!st && st.isConnected !== false;
  } catch {
    return true; // ante la duda no culpamos a la red
  }
}
