import React, {useCallback, useEffect, useRef, useState} from 'react';
import {ActivityIndicator, BackHandler, FlatList, Image, Pressable, StyleSheet, Text, View} from 'react-native';
import {useTVEventHandler} from '@amazon-devices/react-native-kepler';
import {KeplerVideoSurfaceView, VideoPlayer} from '@amazon-devices/react-native-w3cmedia';
import {APP_VERSION, C} from '../lib/theme';
import {Kind, Stream, liveUrl, seriesUrl, vodUrl} from '../lib/xtream';
import {Session, saveLastChannel} from '../lib/session';
import {describeNetwork} from '../lib/net';

interface Props {
  queue: Stream[];
  index: number;
  category: string; // categoria desde la que se abrio ("*" = Todos)
  onExit: () => void;
}

const LIVE_TIMEOUT = 20000;
const VOD_TIMEOUT = 40000;
const ROW_H = 72;

/** En Vega los canales en vivo se piden como MPEG-TS directo (modo URL, sin MSE). */
function urlFor(st: Stream): string {
  const b = Session.base!;
  if (st.type === Kind.LIVE) {
    return liveUrl(b, Session.user, Session.pass, st.id, 'ts');
  }
  if (st.type === Kind.EPISODE) {
    return seriesUrl(b, Session.user, Session.pass, st.id, st.ext || 'mp4');
  }
  return vodUrl(b, Session.user, Session.pass, st.id, st.ext || 'mp4');
}

const fmt = (sec: number) => {
  if (!isFinite(sec) || sec < 0) {
    sec = 0;
  }
  const h = Math.floor(sec / 3600);
  const m = Math.floor((sec % 3600) / 60);
  const s = Math.floor(sec % 60);
  const p2 = (n: number) => (n < 10 ? '0' : '') + n;
  return h > 0 ? `${h}:${p2(m)}:${p2(s)}` : `${p2(m)}:${p2(s)}`;
};

function PanelRow(p: {st: Stream; n: number; current: boolean; preferred: boolean; onFocus: () => void; onPress: () => void}) {
  const [f, setF] = useState(false);
  return (
    <Pressable
      onFocus={() => {
        setF(true);
        p.onFocus();
      }}
      onBlur={() => setF(false)}
      onPress={p.onPress}
      hasTVPreferredFocus={p.preferred}
      style={[s.prow, f && s.focused]}>
      <Text style={s.pnum}>{p.n}</Text>
      <View style={s.plogo}>{p.st.icon ? <Image source={{uri: p.st.icon}} style={s.fill} resizeMode="contain" /> : null}</View>
      <Text numberOfLines={1} style={[s.pname, p.current && !f && s.pcurrent]}>
        {p.st.name}
      </Text>
    </Pressable>
  );
}

export function PlayerScreen({queue, index, category, onExit}: Props) {
  const [idx, setIdx] = useState(index);
  const [phase, setPhase] = useState<'loading' | 'playing' | 'error'>('loading');
  const [buffering, setBuffering] = useState(false);
  const [paused, setPaused] = useState(false);
  const [overlay, setOverlay] = useState(true);
  const [panel, setPanel] = useState(false);
  const [support, setSupport] = useState('');
  const [errDetail, setErrDetail] = useState('');
  const [pos, setPos] = useState({t: 0, d: 0});

  const player = useRef<VideoPlayer | null>(null);
  const surface = useRef<string | null>(null);
  const idxRef = useRef(index);
  const gen = useRef(0);
  const timers = useRef<{load?: any; overlay?: any; panel?: any; zap?: any}>({});
  const started = useRef(false);

  const current = queue[idx];
  const live = current.type === Kind.LIVE;

  // ---- overlay inferior
  const showOverlay = useCallback(() => {
    setOverlay(true);
    clearTimeout(timers.current.overlay);
    timers.current.overlay = setTimeout(() => setOverlay(false), 4500);
  }, []);

  // ---- fallo: mensaje + datos de soporte (red, canal, cuenta, hora, version)
  const fail = useCallback(async (why: string) => {
    clearTimeout(timers.current.load);
    try {
      player.current && player.current.pause();
    } catch {}
    setPhase('error');
    setErrDetail(why);
    const net = await describeNetwork();
    const st = queue[idxRef.current];
    const d = new Date();
    const p2 = (n: number) => (n < 10 ? '0' : '') + n;
    setSupport(
      `${net}\nCanal: ${st.name}   ·   Cuenta: ${Session.user}   ·   ${p2(d.getDate())}/${p2(d.getMonth() + 1)} ${p2(
        d.getHours(),
      )}:${p2(d.getMinutes())}   ·   ${APP_VERSION}`,
    );
  }, [queue]);

  // ---- reproduccion
  const start = useCallback(
    async (i: number) => {
      const g = ++gen.current;
      idxRef.current = i;
      setIdx(i);
      setPhase('loading');
      setPaused(false);
      setErrDetail('');
      clearTimeout(timers.current.load);
      showOverlay();
      let p = player.current;
      if (!p) {
        p = new VideoPlayer();
        await p.initialize();
        if (g !== gen.current) {
          return;
        }
        player.current = p;
        attachListeners(p);
        if (surface.current) {
          p.setSurfaceHandle(surface.current);
        }
      }
      const st = queue[i];
      started.current = false;
      try {
        p.autoplay = true;
        p.src = urlFor(st);
        p.play();
      } catch (e: any) {
        fail('error al iniciar: ' + (e && e.message ? e.message : e));
        return;
      }
      timers.current.load = setTimeout(
        () => {
          if (g === gen.current && !started.current) {
            fail('tiempo de espera agotado');
          }
        },
        st.type === Kind.LIVE ? LIVE_TIMEOUT : VOD_TIMEOUT,
      );
    },
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [queue, fail, showOverlay],
  );

  const attachListeners = (p: VideoPlayer) => {
    p.addEventListener('playing', (() => {
      started.current = true;
      clearTimeout(timers.current.load);
      setPhase('playing');
      setBuffering(false);
      const st = queue[idxRef.current];
      if (st.type === Kind.LIVE) {
        saveLastChannel(st.id, category);
      }
    }) as any);
    p.addEventListener('waiting', (() => setBuffering(true)) as any);
    p.addEventListener('timeupdate', (() => {
      setPos({t: p.currentTime, d: p.duration});
    }) as any);
    p.addEventListener('error', (() => {
      const code = p.error ? (p.error as any).code : '?';
      fail('error del reproductor ' + code);
    }) as any);
    p.addEventListener('ended', (() => {
      const st = queue[idxRef.current];
      if (st.type === Kind.LIVE) {
        start(idxRef.current); // un canal en vivo no deberia terminar: reconecta
      } else if (st.type === Kind.EPISODE && idxRef.current + 1 < queue.length) {
        start(idxRef.current + 1); // serie: siguiente episodio
      } else {
        onExit();
      }
    }) as any);
  };

  useEffect(() => {
    start(index);
    const t = timers.current;
    return () => {
      gen.current++;
      clearTimeout(t.load);
      clearTimeout(t.overlay);
      clearTimeout(t.panel);
      clearTimeout(t.zap);
      const p = player.current;
      player.current = null;
      if (p) {
        try {
          p.pause();
        } catch {}
        p.deinitialize().catch(() => {});
      }
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // ---- cambio de canal: corta ya y abre el nuevo con una pequena espera
  // (libera la conexion anterior en cuentas de 1 conexion; no abre uno por cada canal si se mantiene pulsado)
  const zapTo = (i: number) => {
    if (i < 0 || i >= queue.length) {
      return;
    }
    gen.current++;
    try {
      player.current && player.current.pause();
    } catch {}
    idxRef.current = i;
    setIdx(i);
    setPhase('loading');
    showOverlay();
    clearTimeout(timers.current.zap);
    timers.current.zap = setTimeout(() => start(i), 450);
  };
  const zap = (d: number) => zapTo((idxRef.current + d + queue.length) % queue.length);

  // ---- lista de canales
  const openPanel = () => {
    if (!live || queue.length < 2) {
      showOverlay();
      return;
    }
    setOverlay(false);
    setPanel(true);
    bumpPanel();
  };
  const bumpPanel = () => {
    clearTimeout(timers.current.panel);
    timers.current.panel = setTimeout(() => setPanel(false), 10000);
  };

  // ---- control remoto
  useTVEventHandler(evt => {
    if (!evt || evt.eventKeyAction === 1) {
      return; // solo al presionar (no al soltar)
    }
    if (panel) {
      if (evt.eventType === 'left' || evt.eventType === 'right') {
        setPanel(false);
      }
      return; // la lista maneja arriba/abajo/OK
    }
    const t = evt.eventType;
    if (phase === 'error' && (t === 'select' || t === 'enter')) {
      start(idxRef.current); // reintentar
      return;
    }
    if (live) {
      if (t === 'up' || t === 'channel_up') {
        zap(-1);
      } else if (t === 'down' || t === 'channel_down') {
        zap(1);
      } else if (t === 'select' || t === 'enter') {
        openPanel();
      } else {
        showOverlay();
      }
      return;
    }
    const p = player.current;
    if (!p) {
      return;
    }
    if (t === 'select' || t === 'enter' || t === 'playpause') {
      if (p.paused) {
        p.play();
        setPaused(false);
      } else {
        p.pause();
        setPaused(true);
      }
      showOverlay();
    } else if (t === 'left' || t === 'right' || t === 'skip_backward' || t === 'skip_forward') {
      const delta = t === 'left' || t === 'skip_backward' ? -10 : 10;
      const d = p.duration;
      if (isFinite(d) && d > 0) {
        p.currentTime = Math.max(0, Math.min(p.currentTime + delta, d - 1));
      }
      showOverlay();
    } else {
      showOverlay();
    }
  });

  useEffect(() => {
    const sub = BackHandler.addEventListener('hardwareBackPress', () => {
      if (panel) {
        setPanel(false);
        return true;
      }
      onExit();
      return true;
    });
    return () => sub.remove();
  }, [panel, onExit]);

  const keys = live ? 'OK: reintentar     ↑ ↓: otro canal     ATRÁS: volver' : 'OK: reintentar     ATRÁS: volver';

  return (
    <View style={s.root}>
      <KeplerVideoSurfaceView
        style={s.video}
        onSurfaceViewCreated={(h: string) => {
          surface.current = h;
          player.current && player.current.setSurfaceHandle(h);
        }}
        onSurfaceViewDestroyed={(h: string) => {
          surface.current = null;
          player.current && player.current.clearSurfaceHandle(h);
        }}
      />

      {(phase === 'loading' || buffering) && phase !== 'error' ? (
        <View style={s.centerAbs}>
          <ActivityIndicator color={C.accentLight} size="large" />
          {phase === 'loading' ? <Text style={s.status}>Cargando…</Text> : null}
        </View>
      ) : null}

      {phase === 'error' ? (
        <View style={s.centerAbs}>
          <View style={s.errBox}>
            <Text style={s.errHead}>
              {live ? 'No pudimos reproducir este canal en este momento.' : 'No pudimos reproducir este contenido en este momento.'}
            </Text>
            {Session.devMode && errDetail ? <Text style={s.errSmall}>Detalle: {errDetail}</Text> : null}
            <Text style={s.errKeys}>{keys}</Text>
            <Text style={s.errSmall}>{support}</Text>
          </View>
        </View>
      ) : null}

      {overlay && !panel ? (
        <View style={s.overlay}>
          <Text style={s.title} numberOfLines={1}>
            {current.name}
          </Text>
          <Text style={s.info}>
            {live
              ? 'EN VIVO   •   ↑ ↓ cambiar canal   •   OK: lista de canales'
              : `${paused ? 'Pausado' : 'Reproduciendo'}   ${fmt(pos.t)} / ${fmt(pos.d)}   •   ← → adelantar / retroceder`}
          </Text>
          {!live && pos.d > 0 ? (
            <View style={s.bar}>
              <View style={[s.barFill, {width: `${Math.min(100, (pos.t / pos.d) * 100)}%`}]} />
            </View>
          ) : null}
        </View>
      ) : null}

      {panel ? (
        <View style={s.panel}>
          <Text style={s.panelTitle}>Canales</Text>
          <Text style={s.panelHint}>↑ ↓ elegir   •   OK: ver   •   ATRÁS: cerrar</Text>
          <FlatList
            data={queue}
            keyExtractor={(st, i) => st.id + ':' + i}
            initialScrollIndex={Math.max(0, idx - 3)}
            getItemLayout={(_, i) => ({length: ROW_H, offset: ROW_H * i, index: i})}
            renderItem={({item, index: i}) => (
              <PanelRow
                st={item}
                n={i + 1}
                current={i === idx}
                preferred={i === idx}
                onFocus={bumpPanel}
                onPress={() => {
                  setPanel(false);
                  if (i !== idxRef.current) {
                    zapTo(i);
                  }
                }}
              />
            )}
          />
        </View>
      ) : null}
    </View>
  );
}

const s = StyleSheet.create({
  root: {flex: 1, backgroundColor: '#000'},
  video: {position: 'absolute', left: 0, top: 0, right: 0, bottom: 0, zIndex: 0},
  fill: {width: '100%', height: '100%'},
  centerAbs: {position: 'absolute', left: 0, right: 0, top: 0, bottom: 0, alignItems: 'center', justifyContent: 'center'},
  status: {color: C.text, fontSize: 24, marginTop: 16},
  errBox: {width: 1100, backgroundColor: 'rgba(0,0,0,0.9)', borderRadius: 20, padding: 40},
  errHead: {color: C.text, fontSize: 32, textAlign: 'center'},
  errKeys: {color: C.text, fontSize: 24, textAlign: 'center', marginTop: 24},
  errSmall: {color: C.muted, fontSize: 20, textAlign: 'center', marginTop: 20},
  overlay: {position: 'absolute', left: 0, right: 0, bottom: 0, backgroundColor: 'rgba(0,0,0,0.7)', paddingHorizontal: 72, paddingVertical: 36},
  title: {color: C.text, fontSize: 38},
  info: {color: C.muted, fontSize: 22, marginTop: 6},
  bar: {height: 8, backgroundColor: '#333', borderRadius: 4, marginTop: 16, overflow: 'hidden'},
  barFill: {height: 8, backgroundColor: C.accentLight},
  panel: {position: 'absolute', left: 0, top: 0, bottom: 0, width: 600, backgroundColor: 'rgba(10,23,34,0.94)', paddingHorizontal: 24, paddingTop: 36, paddingBottom: 16},
  panelTitle: {color: C.text, fontSize: 32},
  panelHint: {color: C.muted, fontSize: 19, marginBottom: 14},
  prow: {height: ROW_H - 6, marginBottom: 6, flexDirection: 'row', alignItems: 'center', borderRadius: 10, paddingHorizontal: 10},
  focused: {backgroundColor: C.accent},
  pnum: {width: 56, color: C.muted, fontSize: 20, textAlign: 'right'},
  plogo: {width: 64, height: 52, marginLeft: 14},
  pname: {color: C.text, fontSize: 24, marginLeft: 16, flex: 1},
  pcurrent: {color: C.accentLight},
});
