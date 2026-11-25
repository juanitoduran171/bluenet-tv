import React, {useCallback, useEffect, useMemo, useRef, useState} from 'react';
import {ActivityIndicator, BackHandler, FlatList, Image, Pressable, StyleSheet, Text, View} from 'react-native';
import {Btn, Field} from '../components/ui';
import {PinDialog, PinMode} from '../components/PinDialog';
import {APP_VERSION, C} from '../lib/theme';
import {ApiError, Category, Kind, Stream, categories, streams} from '../lib/xtream';
import {Session, logout, saveSession} from '../lib/session';
import {Parental, hasPin, isAdult} from '../lib/parental';
import {describeNetwork} from '../lib/net';

const ALL: Category = {id: '*', name: 'Todos'};
const ROW_H = 84;
const CAT_H = 58;
const POSTER_W = 200;
const POSTER_H = 340;

interface Props {
  active: boolean; // es la pantalla visible (las demas quedan debajo)
  resume: boolean; // al abrir la app: retomar el ultimo canal
  onOpenSeries: (s: Stream) => void;
  onPlay: (queue: Stream[], index: number, category: string) => void;
  onLogout: () => void;
}

// ------------------------------------------------------------------ elementos de lista

function CatItem(p: {cat: Category; current: boolean; onFocus: () => void; onPress: () => void; preferred: boolean}) {
  const [f, setF] = useState(false);
  const label = isAdult(p.cat.name) ? (Parental.unlocked ? '🔓 ' : '🔒 ') + p.cat.name : p.cat.name;
  return (
    <Pressable
      onFocus={() => {
        setF(true);
        p.onFocus();
      }}
      onBlur={() => setF(false)}
      onPress={p.onPress}
      hasTVPreferredFocus={p.preferred}
      style={[s.cat, f && s.itemFocused]}>
      <Text numberOfLines={1} style={[s.catText, p.current && !f && s.catCurrent]}>
        {label}
      </Text>
    </Pressable>
  );
}

function ChannelRow(p: {item: Stream; onPress: () => void}) {
  const [f, setF] = useState(false);
  return (
    <Pressable onFocus={() => setF(true)} onBlur={() => setF(false)} onPress={p.onPress} style={[s.row, f && s.itemFocused]}>
      <View style={s.rowIcon}>
        {p.item.icon ? <Image source={{uri: p.item.icon}} style={s.rowIconImg} resizeMode="contain" /> : null}
      </View>
      <Text numberOfLines={1} style={s.rowText}>
        {p.item.name}
      </Text>
    </Pressable>
  );
}

function PosterCard(p: {item: Stream; onPress: () => void}) {
  const [f, setF] = useState(false);
  return (
    <Pressable onFocus={() => setF(true)} onBlur={() => setF(false)} onPress={p.onPress} style={[s.card, f && s.itemFocused]}>
      <View style={s.poster}>
        {p.item.icon ? <Image source={{uri: p.item.icon}} style={s.posterImg} resizeMode="cover" /> : null}
      </View>
      <Text numberOfLines={2} style={s.cardText}>
        {p.item.name}
      </Text>
    </Pressable>
  );
}

// ------------------------------------------------------------------ pantalla

export function HomeScreen({active, resume, onOpenSeries, onPlay, onLogout}: Props) {
  const [kind, setKind] = useState<Kind>(Kind.LIVE);
  const [cats, setCats] = useState<Category[]>([]);
  const [loadedCat, setLoadedCat] = useState(-1);
  const [items, setItems] = useState<Stream[]>([]);
  const [query, setQuery] = useState('');
  const [loading, setLoading] = useState(false);
  const [message, setMessage] = useState<{text: string; action?: () => void; hint?: string} | null>(null);
  const [dialog, setDialog] = useState<{mode: PinMode; onDone: () => void} | null>(null);
  const [lockState, setLockState] = useState(Parental.unlocked); // fuerza repintado al bloquear/desbloquear
  const [net, setNet] = useState('');
  const [devMode, setDevMode] = useState(Session.devMode);

  const catCache = useRef(new Map<string, Category[]>());
  const streamCache = useRef(new Map<string, Stream[]>());
  const token = useRef(0);
  const pending = useRef<ReturnType<typeof setTimeout> | null>(null);
  const taps = useRef({n: 0, first: 0});
  const firstFocusDone = useRef(false);
  const kindKey = kind === Kind.LIVE ? 'L' : kind === Kind.SERIES ? 'S' : 'V';

  // ---- red al pie
  useEffect(() => {
    if (!active) {
      return;
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
  }, [active]);

  // ---- al volver a esta pantalla: el control parental pudo bloquearse (app en segundo plano)
  useEffect(() => {
    if (active && lockState !== Parental.unlocked) {
      setLockState(Parental.unlocked);
    }
  }, [active, lockState]);

  // si se volvio a bloquear estando dentro de una categoria protegida, mostrar el candado
  useEffect(() => {
    if (!Parental.unlocked && loadedCat >= 0 && loadedCat < cats.length && isAdult(cats[loadedCat].name) && items.length > 0) {
      loadStreams(cats, loadedCat, kind);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [lockState]);

  const isLocked = useCallback(
    (c: Category | undefined) => !!c && !Parental.unlocked && isAdult(c.name),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [lockState],
  );

  // ---- carga de datos
  const requestAccess = useCallback(async (onGranted: () => void) => {
    if (Parental.unlocked) {
      onGranted();
      return;
    }
    const exists = await hasPin();
    setDialog({
      mode: exists ? 'enter' : 'create',
      onDone: () => {
        setLockState(Parental.unlocked);
        onGranted();
      },
    });
  }, []);

  const loadStreams = useCallback(
    async (list: Category[], idx: number, k: Kind) => {
      if (idx < 0 || idx >= list.length) {
        return;
      }
      setLoadedCat(idx);
      const cat = list[idx];
      const my = ++token.current;
      if (!Parental.unlocked && isAdult(cat.name)) {
        setItems([]);
        setLoading(false);
        setMessage({
          text: 'Categoría protegida con control parental.',
          hint: 'Presiona OK para ingresar la contraseña',
          action: () => requestAccess(() => loadStreams(list, idx, k)),
        });
        return;
      }
      const key = (k === Kind.LIVE ? 'L' : k === Kind.SERIES ? 'S' : 'V') + cat.id;
      const cached = streamCache.current.get(key);
      if (cached) {
        setMessage(null);
        setItems(cached);
        return;
      }
      setLoading(true);
      setMessage(null);
      try {
        const res = await streams(Session.base!, Session.user, Session.pass, k, cat.id === ALL.id ? null : cat.id);
        if (my !== token.current) {
          return;
        }
        streamCache.current.set(key, res);
        setItems(res);
      } catch (e: any) {
        if (my !== token.current) {
          return;
        }
        setItems([]);
        setMessage({
          text: Session.devMode && e instanceof ApiError ? e.message : 'No se pudo cargar el contenido.',
          hint: 'Presiona OK para reintentar',
          action: () => loadStreams(list, idx, k),
        });
      } finally {
        if (my === token.current) {
          setLoading(false);
        }
      }
    },
    [requestAccess],
  );

  const loadCategories = useCallback(
    async (k: Kind) => {
      const key = k === Kind.LIVE ? 'L' : k === Kind.SERIES ? 'S' : 'V';
      const my = ++token.current;
      let list = catCache.current.get(key);
      if (!list) {
        setLoading(true);
        setMessage(null);
        try {
          list = await categories(Session.base!, Session.user, Session.pass, k);
          catCache.current.set(key, list);
        } catch (e: any) {
          if (my === token.current) {
            setLoading(false);
            setMessage({
              text: Session.devMode && e instanceof ApiError ? e.message : 'No se pudo cargar el contenido.',
              hint: 'Presiona OK para reintentar',
              action: () => loadCategories(k),
            });
          }
          return;
        }
        if (my !== token.current) {
          return;
        }
      }
      const full = [ALL, ...list];
      setCats(full);
      loadStreams(full, 0, k);
    },
    [loadStreams],
  );

  const switchKind = (k: Kind) => {
    if (k === kind && cats.length > 0) {
      return;
    }
    setKind(k);
    setQuery('');
    setItems([]);
    setLoadedCat(-1);
    loadCategories(k);
  };

  useEffect(() => {
    loadCategories(Kind.LIVE);
    if (resume && Session.lastChanId) {
      resumeLastChannel();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  /** Al abrir la app: vuelve al ultimo canal (salvo que sea de una categoria protegida). */
  const resumeLastChannel = async () => {
    const id = Session.lastChanId;
    const cat = Session.lastChanCat || '*';
    try {
      const lc = await categories(Session.base!, Session.user, Session.pass, Kind.LIVE);
      const adult = new Set(lc.filter(c => isAdult(c.name)).map(c => c.id));
      if (adult.has(cat)) {
        return;
      }
      const all = await streams(Session.base!, Session.user, Session.pass, Kind.LIVE, cat === '*' ? null : cat);
      const q = all.filter(st => Parental.unlocked || !adult.has(st.categoryId));
      const idx = q.findIndex(st => st.id === id);
      if (idx >= 0) {
        onPlay(q, idx, cat);
      }
    } catch {
      // si falla, simplemente queda en el menu
    }
  };

  // ---- contenido visible (filtro de busqueda + control parental)
  const shown = useMemo(() => {
    const hidden = new Set(Parental.unlocked ? [] : cats.filter(c => isAdult(c.name)).map(c => c.id));
    const q = query.trim().toLowerCase();
    return items.filter(st => !hidden.has(st.categoryId) && (!q || st.name.toLowerCase().includes(q)));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [items, query, cats, lockState]);

  // ---- acciones
  const onCatFocus = (idx: number) => {
    if (pending.current) {
      clearTimeout(pending.current);
    }
    if (idx === loadedCat) {
      return;
    }
    // al recorrer las categorias con el control cargamos con un pequeno retraso
    pending.current = setTimeout(() => loadStreams(cats, idx, kind), 350);
  };

  const onCatPress = (idx: number) => {
    if (pending.current) {
      clearTimeout(pending.current);
    }
    if (isLocked(cats[idx])) {
      setLoadedCat(idx);
      requestAccess(() => loadStreams(cats, idx, kind));
    } else {
      loadStreams(cats, idx, kind);
    }
  };

  const open = (st: Stream, index: number) => {
    if (st.type === Kind.SERIES) {
      onOpenSeries(st);
      return;
    }
    const cat = loadedCat >= 0 && loadedCat < cats.length ? cats[loadedCat].id : '*';
    onPlay(shown, index, cat);
  };

  const onLogoPress = () => {
    const now = Date.now();
    if (now - taps.current.first > 6000) {
      taps.current = {n: 0, first: now};
    }
    if (++taps.current.n >= 7) {
      taps.current.n = 0;
      Session.devMode = !Session.devMode;
      setDevMode(Session.devMode);
      saveSession();
    }
  };

  const onParentalButton = async () => {
    const exists = await hasPin();
    setDialog({
      mode: exists ? 'menu' : 'create',
      onDone: () => {
        setLockState(Parental.unlocked);
        if (loadedCat >= 0) {
          loadStreams(cats, loadedCat, kind);
        }
      },
    });
  };

  // ---- boton atras: limpia la busqueda antes de salir
  useEffect(() => {
    if (!active) {
      return;
    }
    const sub = BackHandler.addEventListener('hardwareBackPress', () => {
      if (dialog) {
        setDialog(null);
        return true;
      }
      if (query) {
        setQuery('');
        return true;
      }
      return false;
    });
    return () => sub.remove();
  }, [active, query, dialog]);

  if (dialog) {
    return (
      <PinDialog
        mode={dialog.mode}
        onDone={() => {
          const d = dialog;
          setDialog(null);
          d.onDone();
        }}
        onCancel={() => setDialog(null)}
      />
    );
  }

  const live = kind === Kind.LIVE;
  const preferredCat = !firstFocusDone.current && cats.length > 0;
  if (preferredCat) {
    firstFocusDone.current = true;
  }

  return (
    <View style={s.root}>
      {/* ---- barra superior */}
      <View style={s.top}>
        <Pressable onPress={onLogoPress} style={s.logoBtn}>
          <Image source={require('../assets/logo.png')} style={s.logo} resizeMode="contain" />
        </Pressable>
        <Btn label="TV en vivo" selected={kind === Kind.LIVE} onPress={() => switchKind(Kind.LIVE)} style={s.tab} />
        <Btn label="Películas" selected={kind === Kind.VOD} onPress={() => switchKind(Kind.VOD)} style={s.tab} />
        <Btn label="Series" selected={kind === Kind.SERIES} onPress={() => switchKind(Kind.SERIES)} style={s.tab} />
        <View style={s.spacer} />
        <Field value={query} onChangeText={setQuery} placeholder="Buscar" style={s.search} />
        <Btn small label="Control parental" onPress={onParentalButton} style={s.tab} />
        <Btn
          small
          label="Salir"
          style={s.tab}
          onPress={async () => {
            await logout();
            onLogout();
          }}
        />
      </View>

      {/* ---- cuerpo */}
      <View style={s.body}>
        <View style={s.cats}>
          <FlatList
            data={cats}
            keyExtractor={(c, i) => kindKey + c.id + ':' + i}
            renderItem={({item, index}) => (
              <CatItem
                cat={item}
                current={index === loadedCat}
                preferred={preferredCat && index === 0}
                onFocus={() => onCatFocus(index)}
                onPress={() => onCatPress(index)}
              />
            )}
            getItemLayout={(_, i) => ({length: CAT_H, offset: CAT_H * i, index: i})}
            extraData={[loadedCat, lockState]}
          />
        </View>
        <View style={s.content}>
          {loading ? (
            <ActivityIndicator color={C.accentLight} size="large" style={s.center} />
          ) : message ? (
            <View style={s.center}>
              {message.action ? (
                <Btn label={message.text + '\n' + (message.hint || '')} onPress={message.action} style={s.msgBtn} textStyle={s.msgText} multiline />
              ) : (
                <Text style={s.msgText}>{message.text}</Text>
              )}
            </View>
          ) : shown.length === 0 && loadedCat >= 0 ? (
            <Text style={[s.msgText, s.center]}>
              {query ? `Sin resultados para "${query}".` : 'No hay contenido en esta categoría.'}
            </Text>
          ) : live ? (
            <FlatList
              data={shown}
              keyExtractor={(it, i) => it.id + ':' + i}
              renderItem={({item, index}) => <ChannelRow item={item} onPress={() => open(item, index)} />}
              getItemLayout={(_, i) => ({length: ROW_H, offset: ROW_H * i, index: i})}
              initialNumToRender={12}
              windowSize={7}
            />
          ) : (
            <FlatList
              key={'grid' + kindKey}
              data={shown}
              numColumns={6}
              keyExtractor={(it, i) => it.id + ':' + i}
              renderItem={({item, index}) => <PosterCard item={item} onPress={() => open(item, index)} />}
              initialNumToRender={18}
              windowSize={5}
            />
          )}
        </View>
      </View>

      {/* ---- pie: red y cuenta (para soporte) */}
      <Text style={s.footer} numberOfLines={1}>
        {net}
        {'   ·   Cuenta: '}
        {Session.user}
        {'   ·   '}
        {APP_VERSION}
        {devMode ? '   ·   MODO TÉCNICO' : ''}
      </Text>
    </View>
  );
}

const s = StyleSheet.create({
  root: {flex: 1, backgroundColor: C.bg, paddingHorizontal: 48, paddingTop: 28, paddingBottom: 16},
  top: {flexDirection: 'row', alignItems: 'center', height: 76},
  logoBtn: {width: 72, height: 72, borderRadius: 36, marginRight: 16},
  logo: {width: 72, height: 72},
  tab: {marginLeft: 12},
  spacer: {flex: 1},
  search: {width: 260, height: 56, marginLeft: 12},
  body: {flex: 1, flexDirection: 'row', marginTop: 20},
  cats: {width: 360, backgroundColor: C.panel, borderRadius: 16, padding: 8},
  cat: {height: CAT_H - 4, marginBottom: 4, borderRadius: 10, justifyContent: 'center', paddingHorizontal: 18},
  catText: {color: C.text, fontSize: 22},
  catCurrent: {color: C.accentLight},
  itemFocused: {backgroundColor: C.accent},
  content: {flex: 1, marginLeft: 24},
  center: {flex: 1, alignSelf: 'center', textAlignVertical: 'center', justifyContent: 'center'},
  msgBtn: {paddingVertical: 20, paddingHorizontal: 30, backgroundColor: 'transparent'},
  msgText: {color: C.muted, fontSize: 24, textAlign: 'center'},
  row: {height: ROW_H - 6, marginBottom: 6, flexDirection: 'row', alignItems: 'center', borderRadius: 12, paddingHorizontal: 12},
  rowIcon: {width: 72, height: 64, borderRadius: 8, backgroundColor: C.panel, padding: 4},
  rowIconImg: {width: '100%', height: '100%'},
  rowText: {color: C.text, fontSize: 26, marginLeft: 20, flex: 1},
  card: {width: POSTER_W, height: POSTER_H, margin: 6, padding: 8, borderRadius: 12},
  poster: {width: '100%', height: POSTER_H - 86, borderRadius: 8, backgroundColor: C.panel, overflow: 'hidden'},
  posterImg: {width: '100%', height: '100%'},
  cardText: {color: C.text, fontSize: 18, marginTop: 6},
  footer: {color: C.muted, fontSize: 17, marginTop: 10},
});
