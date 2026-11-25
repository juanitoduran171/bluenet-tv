import React, {useCallback, useEffect, useRef, useState} from 'react';
import {AppState, BackHandler, StyleSheet, View} from 'react-native';
import {LoginScreen} from './screens/LoginScreen';
import {HomeScreen} from './screens/HomeScreen';
import {SeriesScreen} from './screens/SeriesScreen';
import {PlayerScreen} from './screens/PlayerScreen';
import {Stream} from './lib/xtream';
import {loadSession} from './lib/session';
import {Parental} from './lib/parental';
import {C} from './lib/theme';

type Route =
  | {key: number; name: 'login'; noAuto?: boolean}
  | {key: number; name: 'home'; resume: boolean}
  | {key: number; name: 'series'; series: Stream}
  | {key: number; name: 'player'; queue: Stream[]; index: number; category: string};

type RouteInput = Route extends infer R ? (R extends {key: number} ? Omit<R, 'key'> : never) : never;

/**
 * Navegacion por pila: la pantalla de arriba es la visible; las de abajo quedan montadas pero ocultas
 * (asi el menu conserva la categoria y la posicion al volver del reproductor).
 */
export const App = () => {
  const [ready, setReady] = useState(false);
  const [stack, setStack] = useState<Route[]>([]);
  const nextKey = useRef(1);

  const make = (r: RouteInput): Route => ({...r, key: nextKey.current++} as Route);
  const push = useCallback((r: RouteInput) => setStack(s => [...s, make(r)]), []);
  const pop = useCallback(() => setStack(s => (s.length > 1 ? s.slice(0, -1) : s)), []);
  const reset = useCallback((r: RouteInput) => setStack([make(r)]), []);

  useEffect(() => {
    loadSession().then(() => {
      setStack([make({name: 'login'})]);
      setReady(true);
    });
    // Al pasar a segundo plano se vuelve a bloquear el control parental.
    const sub = AppState.addEventListener('change', st => {
      if (st !== 'active') {
        Parental.unlocked = false;
      }
    });
    return () => sub.remove();
  }, []);

  // ATRAS general: vuelve a la pantalla anterior; en la primera, sale de la app.
  // (las pantallas registran su propio manejador despues, y tienen prioridad)
  useEffect(() => {
    const sub = BackHandler.addEventListener('hardwareBackPress', () => {
      if (stack.length > 1) {
        pop();
        return true;
      }
      return false;
    });
    return () => sub.remove();
  }, [stack.length, pop]);

  if (!ready) {
    return <View style={styles.root} />;
  }

  return (
    <View style={styles.root}>
      {stack.map((r, i) => {
        const top = i === stack.length - 1;
        let screen: React.ReactNode = null;
        switch (r.name) {
          case 'login':
            screen = <LoginScreen noAuto={r.noAuto} onLoggedIn={() => reset({name: 'home', resume: true})} />;
            break;
          case 'home':
            screen = (
              <HomeScreen
                active={top}
                resume={r.resume}
                onOpenSeries={series => push({name: 'series', series})}
                onPlay={(queue, index, category) => push({name: 'player', queue, index, category})}
                onLogout={() => reset({name: 'login', noAuto: true})}
              />
            );
            break;
          case 'series':
            screen = (
              <SeriesScreen series={r.series} onPlay={(queue, index) => push({name: 'player', queue, index, category: ''})} />
            );
            break;
          case 'player':
            screen = <PlayerScreen queue={r.queue} index={r.index} category={r.category} onExit={pop} />;
            break;
        }
        return (
          <View key={r.key} style={[styles.layer, !top && styles.hidden]}>
            {screen}
          </View>
        );
      })}
    </View>
  );
};

const styles = StyleSheet.create({
  root: {flex: 1, backgroundColor: C.bg},
  layer: {position: 'absolute', left: 0, top: 0, right: 0, bottom: 0},
  hidden: {display: 'none'},
});
