import React, {useEffect, useState} from 'react';
import {ActivityIndicator, FlatList, Image, Pressable, ScrollView, StyleSheet, Text, View} from 'react-native';
import {Btn} from '../components/ui';
import {C} from '../lib/theme';
import {ApiError, Episode, Kind, SeriesInfo, Stream, seriesInfo} from '../lib/xtream';
import {Session} from '../lib/session';

interface Props {
  series: Stream;
  onPlay: (queue: Stream[], index: number) => void;
}

const EP_H = 120;

function EpisodeRow(p: {ep: Episode; fallbackImage: string; preferred: boolean; onPress: () => void}) {
  const [f, setF] = useState(false);
  const img = p.ep.image || p.fallbackImage;
  const sub = [p.ep.duration, p.ep.plot].filter(x => x).join('  ·  ');
  return (
    <Pressable
      onFocus={() => setF(true)}
      onBlur={() => setF(false)}
      onPress={p.onPress}
      hasTVPreferredFocus={p.preferred}
      style={[s.ep, f && s.focused]}>
      <View style={s.thumb}>{img ? <Image source={{uri: img}} style={s.fill} resizeMode="cover" /> : null}</View>
      <View style={s.epCol}>
        <Text numberOfLines={1} style={s.epTitle}>
          {label(p.ep)}
        </Text>
        <Text numberOfLines={2} style={s.epSub}>
          {sub}
        </Text>
      </View>
    </Pressable>
  );
}

const label = (e: Episode) => 'E' + e.number + (e.title ? '  ·  ' + e.title : '');

export function SeriesScreen({series, onPlay}: Props) {
  const [info, setInfo] = useState<SeriesInfo | null>(null);
  const [season, setSeason] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  const load = async () => {
    setLoading(true);
    setError(null);
    try {
      setInfo(await seriesInfo(Session.base!, Session.user, Session.pass, series.id));
    } catch (e: any) {
      setError(Session.devMode && e instanceof ApiError ? e.message : 'No se pudo cargar la serie.');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [series.id]);

  const episodes = info && info.seasons[season] ? info.seasons[season].episodes : [];

  const play = (index: number) => {
    const queue: Stream[] = episodes.map(e => ({
      id: e.id,
      name: `T${e.season} · ${label(e)}`,
      icon: e.image,
      ext: e.ext,
      rating: '',
      categoryId: '',
      type: Kind.EPISODE,
    }));
    onPlay(queue, index);
  };

  const meta = info
    ? [info.year, info.genre, info.rating && info.rating !== '0' ? '★ ' + info.rating : ''].filter(x => x).join('  ·  ')
    : '';
  const cover = (info && info.cover) || series.icon;

  return (
    <View style={s.root}>
      {/* ---- izquierda: caratula y datos */}
      <View style={s.left}>
        <View style={s.poster}>{cover ? <Image source={{uri: cover}} style={s.fill} resizeMode="cover" /> : null}</View>
        <Text style={s.title} numberOfLines={2}>
          {(info && info.name) || series.name}
        </Text>
        <Text style={s.meta}>{meta}</Text>
        <Text style={s.plot} numberOfLines={8}>
          {info ? info.plot : ''}
        </Text>
      </View>

      {/* ---- derecha: temporadas y episodios */}
      <View style={s.right}>
        {loading ? (
          <ActivityIndicator color={C.accentLight} size="large" style={s.center} />
        ) : error ? (
          <View style={s.center}>
            <Btn label={error + '\nPresiona OK para reintentar'} onPress={load} multiline hasTVPreferredFocus />
          </View>
        ) : !info || info.seasons.length === 0 ? (
          <Text style={s.msg}>Esta serie todavía no tiene episodios disponibles.</Text>
        ) : (
          <>
            <ScrollView horizontal style={s.seasons} contentContainerStyle={s.seasonsRow}>
              {info.seasons.map((se, i) => (
                // al pasar por una temporada con el control se muestran sus episodios
                <Btn key={se.number} small label={se.name} selected={i === season} onFocus={() => setSeason(i)} onPress={() => setSeason(i)} style={s.seasonBtn} />
              ))}
            </ScrollView>
            <FlatList
              key={'s' + season}
              data={episodes}
              keyExtractor={e => e.id}
              renderItem={({item, index}) => (
                <EpisodeRow ep={item} fallbackImage={series.icon} preferred={index === 0} onPress={() => play(index)} />
              )}
              getItemLayout={(_, i) => ({length: EP_H, offset: EP_H * i, index: i})}
            />
          </>
        )}
      </View>
    </View>
  );
}

const s = StyleSheet.create({
  root: {flex: 1, flexDirection: 'row', backgroundColor: C.bg, paddingHorizontal: 56, paddingVertical: 40},
  left: {width: 340},
  poster: {width: 300, height: 440, borderRadius: 12, backgroundColor: C.panel, overflow: 'hidden'},
  fill: {width: '100%', height: '100%'},
  title: {color: C.text, fontSize: 30, marginTop: 16},
  meta: {color: C.accentLight, fontSize: 19, marginTop: 6},
  plot: {color: C.muted, fontSize: 19, marginTop: 12},
  right: {flex: 1, marginLeft: 36},
  seasons: {flexGrow: 0, marginBottom: 16},
  seasonsRow: {alignItems: 'center'},
  seasonBtn: {marginRight: 12},
  ep: {height: EP_H - 8, marginBottom: 8, flexDirection: 'row', alignItems: 'center', borderRadius: 12, padding: 8},
  focused: {backgroundColor: C.accent},
  thumb: {width: 176, height: 99, borderRadius: 8, backgroundColor: C.panel, overflow: 'hidden'},
  epCol: {flex: 1, marginLeft: 20},
  epTitle: {color: C.text, fontSize: 24},
  epSub: {color: C.muted, fontSize: 18, marginTop: 4},
  center: {flex: 1, alignSelf: 'center', justifyContent: 'center'},
  msg: {color: C.muted, fontSize: 24, marginTop: 40},
});
