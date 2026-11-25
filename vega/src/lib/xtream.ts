/**
 * Cliente de la API clasica de Xtream Codes (player_api.php).
 * Port de XtreamClient.java (version Android). Sin dependencias de React Native: se prueba en Node.
 */

export const USER_AGENT = 'BLUEnetTV/2.2 (Vega)';

export enum Kind {
  LIVE = 0,
  VOD = 1,
  SERIES = 2, // una serie (abre su ficha)
  EPISODE = 3, // un episodio (se reproduce)
}

export interface Category {
  id: string;
  name: string;
}

export interface Stream {
  id: string;
  name: string;
  icon: string;
  ext: string; // VOD/episodio: container_extension
  rating: string;
  categoryId: string;
  type: Kind;
}

export interface Episode {
  id: string;
  season: number;
  number: number;
  title: string;
  ext: string;
  plot: string;
  image: string;
  duration: string;
}

export interface Season {
  number: number;
  name: string;
  episodes: Episode[];
}

export interface SeriesInfo {
  name: string;
  plot: string;
  cover: string;
  genre: string;
  year: string;
  rating: string;
  seasons: Season[];
}

export interface Auth {
  base: string;
  expTs: number; // epoch en segundos, 0 = sin dato
  maxConnections: number;
  formats: string[];
}

export enum ErrKind {
  NETWORK = 0, // no hubo respuesta del servidor
  CREDENTIALS = 1, // el servidor dijo explicitamente que la clave es incorrecta
  ACCOUNT = 2, // cuenta vencida / deshabilitada
  RESPONSE = 3, // respuesta que no es Xtream
  DENIED = 4, // el servidor respondio con error HTTP (403, 5xx...): clave erronea o falla del servicio
}

export class ApiError extends Error {
  kind: ErrKind;
  constructor(kind: ErrKind, message: string) {
    super(message);
    this.kind = kind;
  }
}

class HttpStatusError extends Error {
  status: number;
  constructor(status: number) {
    super('HTTP ' + status);
    this.status = status;
  }
}

// ------------------------------------------------------------------ utilidades

/** Valor de texto de un campo JSON; null/undefined -> "". */
export function str(o: any, key: string): string {
  if (o == null || typeof o !== 'object') {
    return '';
  }
  const v = o[key];
  if (v === null || v === undefined) {
    return '';
  }
  return String(v).trim();
}

function intOr(s: string, fallback: number): number {
  const n = parseInt(String(s).trim(), 10);
  return isNaN(n) ? fallback : n;
}

const enc = (s: string) => encodeURIComponent(s == null ? '' : s);

async function httpGet(url: string, timeoutMs: number): Promise<string> {
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), timeoutMs);
  try {
    const res = await fetch(url, {
      headers: {'User-Agent': USER_AGENT, Accept: 'application/json, */*'},
      signal: ctrl.signal,
    });
    if (res.status >= 400) {
      throw new HttpStatusError(res.status);
    }
    return await res.text();
  } finally {
    clearTimeout(timer);
  }
}

// ------------------------------------------------------------------ servidor

/** Normaliza lo escrito y devuelve las bases a probar; con preferred (servidor que ya funciono) primero. */
export function candidates(input: string, preferred?: string | null): string[] {
  const out: string[] = [];
  let s = (input || '').trim();
  if (s.length > 0) {
    const q = s.indexOf('/player_api.php');
    if (q >= 0) {
      s = s.substring(0, q);
    }
    while (s.endsWith('/')) {
      s = s.substring(0, s.length - 1);
    }
    const lower = s.toLowerCase();
    if (lower.startsWith('http://') || lower.startsWith('https://')) {
      out.push(s);
    } else {
      const hasPort = s.indexOf(':') > 0;
      out.push('http://' + s, 'https://' + s);
      if (!hasPort) {
        out.push('http://' + s + ':8080', 'http://' + s + ':25461', 'https://' + s + ':8443');
      }
    }
  }
  if (preferred) {
    const i = out.indexOf(preferred);
    if (i >= 0) {
      out.splice(i, 1);
    }
    out.unshift(preferred);
  }
  return out;
}

export async function authenticate(
  server: string,
  user: string,
  pass: string,
  preferred?: string | null,
): Promise<Auth> {
  const bases = candidates(server, preferred);
  if (bases.length === 0) {
    throw new ApiError(ErrKind.RESPONSE, 'Sin servidor.');
  }
  let lastErr = '';
  let httpSeen = 0;
  let sawNonXtream = false;
  for (const base of bases) {
    let body: string;
    try {
      body = await httpGet(`${base}/player_api.php?username=${enc(user)}&password=${enc(pass)}`, 8000);
    } catch (e: any) {
      if (e instanceof HttpStatusError) {
        if (e.status === 401) {
          throw new ApiError(ErrKind.CREDENTIALS, 'Usuario o contraseña incorrectos.');
        }
        httpSeen = e.status;
        if (base === preferred) {
          throw new ApiError(ErrKind.DENIED, `El servidor rechazó el acceso (HTTP ${e.status}).`);
        }
      }
      lastErr = e && e.message ? e.message : String(e);
      continue;
    }
    let root: any;
    try {
      root = JSON.parse(body.trim());
    } catch {
      sawNonXtream = true;
      continue;
    }
    const ui = root && typeof root === 'object' ? root.user_info : null;
    if (!ui || typeof ui !== 'object') {
      sawNonXtream = true;
      continue;
    }
    if (intOr(str(ui, 'auth'), 0) !== 1) {
      throw new ApiError(ErrKind.CREDENTIALS, 'Usuario o contraseña incorrectos.');
    }
    const status = str(ui, 'status');
    if (status.length > 0 && status.toLowerCase() !== 'active') {
      throw new ApiError(ErrKind.ACCOUNT, `La cuenta no está activa (${status}).`);
    }
    const formats: string[] = Array.isArray(ui.allowed_output_formats)
      ? ui.allowed_output_formats.map((f: any) => String(f).trim().toLowerCase()).filter((f: string) => f)
      : [];
    return {
      base,
      expTs: intOr(str(ui, 'exp_date'), 0),
      maxConnections: intOr(str(ui, 'max_connections'), 0),
      formats,
    };
  }
  if (httpSeen > 0) {
    throw new ApiError(ErrKind.DENIED, `El servidor rechazó el acceso (HTTP ${httpSeen}).`);
  }
  if (sawNonXtream) {
    throw new ApiError(ErrKind.RESPONSE, 'El servidor respondió, pero no parece una API Xtream.');
  }
  throw new ApiError(ErrKind.NETWORK, `No se pudo conectar con el servidor (${lastErr}).`);
}

// ------------------------------------------------------------------ catalogo

function api(base: string, user: string, pass: string, action: string, categoryId?: string | null): string {
  let u = `${base}/player_api.php?username=${enc(user)}&password=${enc(pass)}&action=${action}`;
  if (categoryId != null) {
    u += `&category_id=${enc(categoryId)}`;
  }
  return u;
}

async function getArray(url: string): Promise<any[]> {
  let body: string;
  try {
    body = (await httpGet(url, 60000)).trim();
  } catch (e: any) {
    throw new ApiError(ErrKind.NETWORK, 'Error de red: ' + (e && e.message ? e.message : e));
  }
  if (body.length === 0 || body[0] !== '[') {
    return []; // algunos paneles devuelven {} o false cuando no hay datos
  }
  try {
    const a = JSON.parse(body);
    return Array.isArray(a) ? a : [];
  } catch {
    throw new ApiError(ErrKind.RESPONSE, 'Respuesta inválida del servidor.');
  }
}

export async function categories(base: string, user: string, pass: string, kind: Kind): Promise<Category[]> {
  const action =
    kind === Kind.LIVE ? 'get_live_categories' : kind === Kind.SERIES ? 'get_series_categories' : 'get_vod_categories';
  const arr = await getArray(api(base, user, pass, action));
  const out: Category[] = [];
  for (const o of arr) {
    const id = str(o, 'category_id');
    if (!id) {
      continue;
    }
    const name = str(o, 'category_name');
    out.push({id, name: name || id});
  }
  return out;
}

/** categoryId null = todo el catalogo. */
export async function streams(
  base: string,
  user: string,
  pass: string,
  kind: Kind,
  categoryId: string | null,
): Promise<Stream[]> {
  const live = kind === Kind.LIVE;
  const series = kind === Kind.SERIES;
  const action = live ? 'get_live_streams' : series ? 'get_series' : 'get_vod_streams';
  const arr = await getArray(api(base, user, pass, action, categoryId));
  const out: Stream[] = [];
  for (const o of arr) {
    const id = str(o, series ? 'series_id' : 'stream_id');
    if (!id) {
      continue;
    }
    let icon = str(o, 'stream_icon');
    if (!icon) {
      icon = str(o, 'cover');
    }
    let ext = live || series ? '' : str(o, 'container_extension');
    if (!live && !series && !ext) {
      ext = 'mp4';
    }
    out.push({
      id,
      name: str(o, 'name') || id,
      icon,
      ext,
      rating: str(o, 'rating'),
      categoryId: str(o, 'category_id'),
      type: kind,
    });
  }
  return out;
}

export async function seriesInfo(base: string, user: string, pass: string, seriesId: string): Promise<SeriesInfo> {
  let body: string;
  try {
    body = (
      await httpGet(
        `${base}/player_api.php?username=${enc(user)}&password=${enc(pass)}&action=get_series_info&series_id=${enc(
          seriesId,
        )}`,
        60000,
      )
    ).trim();
  } catch (e: any) {
    throw new ApiError(ErrKind.NETWORK, 'Error de red: ' + (e && e.message ? e.message : e));
  }
  let root: any;
  try {
    root = JSON.parse(body);
  } catch {
    throw new ApiError(ErrKind.RESPONSE, 'Respuesta inválida del servidor.');
  }
  const si: SeriesInfo = {name: '', plot: '', cover: '', genre: '', year: '', rating: '', seasons: []};
  const info = root && root.info && typeof root.info === 'object' && !Array.isArray(root.info) ? root.info : null;
  if (info) {
    si.name = str(info, 'name');
    si.plot = str(info, 'plot');
    si.cover = str(info, 'cover');
    si.genre = str(info, 'genre');
    si.rating = str(info, 'rating');
    const date = str(info, 'releaseDate') || str(info, 'release_date');
    si.year = date.length >= 4 ? date.substring(0, 4) : date;
  }
  const bySeason = new Map<number, Season>();
  const add = (arr: any, seasonFromKey: number) => {
    if (!Array.isArray(arr)) {
      return;
    }
    arr.forEach((o: any, i: number) => {
      if (!o || typeof o !== 'object') {
        return;
      }
      const id = str(o, 'id');
      if (!id) {
        return;
      }
      const sn = intOr(str(o, 'season'), seasonFromKey);
      const season = sn > 0 ? sn : seasonFromKey > 0 ? seasonFromKey : 1;
      const inf = o.info && typeof o.info === 'object' ? o.info : null;
      const ep: Episode = {
        id,
        season,
        number: intOr(str(o, 'episode_num'), i + 1),
        title: str(o, 'title'),
        ext: str(o, 'container_extension') || 'mp4',
        plot: inf ? str(inf, 'plot') : '',
        image: inf ? str(inf, 'movie_image') : '',
        duration: inf ? str(inf, 'duration') : '',
      };
      let s = bySeason.get(season);
      if (!s) {
        s = {number: season, name: 'Temporada ' + season, episodes: []};
        bySeason.set(season, s);
      }
      s.episodes.push(ep);
    });
  };
  const eps = root ? root.episodes : null;
  if (Array.isArray(eps)) {
    // algunos paneles: [[...temporada 1...], [...temporada 2...]]
    eps.forEach((x: any, i: number) => {
      if (Array.isArray(x)) {
        add(x, i + 1);
      } else if (x && typeof x === 'object') {
        add([x], 0);
      }
    });
  } else if (eps && typeof eps === 'object') {
    // formato habitual: {"1": [...], "2": [...]}
    for (const k of Object.keys(eps)) {
      add(eps[k], intOr(k, 0));
    }
  }
  if (root && Array.isArray(root.seasons)) {
    for (const so of root.seasons) {
      const s = bySeason.get(intOr(str(so, 'season_number'), -1));
      const nm = str(so, 'name');
      if (s && nm) {
        s.name = nm;
      }
    }
  }
  si.seasons = Array.from(bySeason.values()).sort((a, b) => a.number - b.number);
  for (const s of si.seasons) {
    s.episodes.sort((a, b) => a.number - b.number);
  }
  return si;
}

// ------------------------------------------------------------------ URLs de reproduccion

export const liveUrl = (base: string, u: string, p: string, id: string, ext: string) =>
  `${base}/live/${enc(u)}/${enc(p)}/${enc(id)}.${ext}`;
export const vodUrl = (base: string, u: string, p: string, id: string, ext: string) =>
  `${base}/movie/${enc(u)}/${enc(p)}/${enc(id)}.${ext}`;
export const seriesUrl = (base: string, u: string, p: string, id: string, ext: string) =>
  `${base}/series/${enc(u)}/${enc(p)}/${enc(id)}.${ext}`;
