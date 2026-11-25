import http from 'node:http';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { performance } from 'node:perf_hooks';
import { isCalendarAirDate, qualifyHierarchy } from './tmdb-hierarchy.mjs';

const [fixturePath, delayValue = '0', portValue = '18091'] = process.argv.slice(2);
const delayMs = Number(delayValue);
const port = Number(portValue);
if (!Number.isInteger(delayMs) || delayMs < 0 || delayMs > 900) throw Error('Delay must be 0–900 ms.');
if (!Number.isInteger(port) || port < 0 || port > 65535) throw Error('Invalid loopback port.');
const manifest = readFileSync(fixturePath, 'utf8').replace(/^\uFEFF/, '');
const { region, entries, catalog } = JSON.parse(manifest);
const watchlist = new Set(entries.map(({ family, id }) => family + '/' + id));
const titlePattern = /^(movie|tv)\/[1-9]\d*$/;
if (!/^[A-Z]{2}$/.test(region) || entries.length !== 276 || watchlist.size !== 276 ||
    [...watchlist].some((key) => !titlePattern.test(key))) {
  throw Error('Invalid Watchlist catalog manifest.');
}

const titles = new Set(watchlist);
for (const { family, id } of catalog?.titles ?? []) {
  const key = family + '/' + id;
  if (!titlePattern.test(key)) throw Error('Invalid catalog title key.');
  titles.add(key);
}

const episodes = new Map();
const seasons = new Map();
const seasonPattern = /^([1-9]\d*)\/(0|[1-9]\d*)$/;
for (const row of catalog?.seasons ?? []) {
  const key = row.series + '/' + row.season;
  if (!seasonPattern.test(key) || seasons.has(key)) throw Error('Invalid or repeated season key.');
  seasons.set(key, { ...row, rows: [], syntheticId: 1_000_000 + seasons.size });
}

for (const row of catalog?.episodes ?? []) {
  const parent = seasons.get(row.series + '/' + row.season);
  const key = row.series + '/' + row.season + '/' + row.episode;
  if (!parent || !Number.isSafeInteger(row.episode) || row.episode < 1 || episodes.has(key)) {
    throw Error('Invalid or repeated episode key.');
  }
  const details = { ...row, syntheticId: 2_000_000 + episodes.size };
  episodes.set(key, details);
  parent.rows.push(details);
}
for (const season of seasons.values()) season.rows.sort((left, right) => left.episode - right.episode);
const { qualifiedSeries, qualifiedSeasons } = qualifyHierarchy(catalog, seasons);

const full = {
  movie: 'credits,watch/providers,alternative_titles,videos,external_ids,release_dates',
  tv: 'aggregate_credits,watch/providers,alternative_titles,videos,external_ids',
  season: 'aggregate_credits,watch/providers',
  episode: 'external_ids',
};
const attempts = new Map();
let nextRequest = 0;

function dateFor(id) {
  return '2005-01-' + String(Number(id.slice(-5)) % 28 + 1).padStart(2, '0');
}
function releaseDates(id) {
  return { id, results: [{ iso_3166_1: region,
    release_dates: [{ release_date: dateFor(id) + 'T00:00:00.000Z', type: 3 }] }] };
}
function episodeData(episode) {
  const qualified = qualifiedSeasons.has(episode.series + '/' + episode.season);
  const verifiedDate = catalog?.episodeDateProvenance === 'v2-calendar-snapshots' &&
    episode.dateEvidence === 'calendar-air-date' && isCalendarAirDate(episode.airDate);
  return {
    id: episode.syntheticId, name: 'Replay tv ' + episode.series + ' S' + episode.season + ' E' + episode.episode,
    overview: '', episode_number: episode.episode, season_number: episode.season,
    air_date: qualified || verifiedDate ? episode.airDate : null,
    replay_hierarchy: qualified ? 'qualified' : 'unqualified',
    runtime: episode.runtime ?? null, still_path: null, guest_stars: [],
  };
}
function seasonSummary(season) {
  return {
    id: season.syntheticId, season_number: season.season, name: 'Replay tv ' + season.series + ' S' + season.season,
    overview: '', air_date: qualifiedSeasons.has(season.series + '/' + season.season)
      ? season.rows.map(({ airDate }) => airDate).sort()[0] : null,
    episode_count: qualifiedSeasons.has(season.series + '/' + season.season) ? season.rows.length : null, poster_path: null,
  };
}

function metadata(family, id, extended) {
  const title = 'Replay ' + family + ' ' + id;
  const date = dateFor(id);
  const base = { id, original_language: 'en', overview: '', poster_path: null, backdrop_path: null,
    vote_average: 7.1, genres: [{ id: 18, name: 'Drama' }] };
  if (family === 'movie') {
    return { ...base, title, original_title: title, release_date: date, runtime: 102, status: 'Released',
      ...(extended ? { release_dates: releaseDates(id) } : {}) };
  }
  const roster = qualifiedSeries.get(id);
  return { ...base, name: title, original_name: title,
    first_air_date: roster ? roster.map(seasonSummary).map(({ air_date }) => air_date).sort()[0] : date,
    episode_run_time: [42], status: 'Ended',
    number_of_seasons: roster?.length ?? null,
    number_of_episodes: roster?.reduce((sum, season) => sum + season.rows.length, 0) ?? null,
    // Series metadata is still used for Watchlist release dates. A null roster
    // reports unavailable hierarchy and keeps Java's completeness check false.
    replay_hierarchy: roster ? 'qualified' : 'unqualified',
    seasons: roster?.map(seasonSummary) ?? null };
}

function identify(pathname) {
  const title = /^\/(movie|tv)\/([1-9]\d*)$/.exec(pathname);
  if (title) return { family: title[1], id: title[2] };
  const hierarchy = /^\/tv\/([1-9]\d*)\/season\/(0|[1-9]\d*)(?:\/episode\/([1-9]\d*))?$/.exec(pathname);
  if (hierarchy) return { family: hierarchy[3] ? 'episode' : 'season', id: hierarchy.slice(1).filter(Boolean).join('/') };
  const release = /^\/movie\/([1-9]\d*)\/release_dates$/.exec(pathname);
  if (release) return { family: 'release_dates', id: release[1] };
  const trending = /^\/trending\/(movie|tv)\/(day|week)$/.exec(pathname);
  if (trending) return { family: 'trending', id: trending[1] + '/' + trending[2] };
  return null;
}

function payload(family, id, variant, page) {
  switch (family) {
    case 'season': {
      const season = seasons.get(id);
      const qualified = qualifiedSeasons.has(id);
      if (!season || !titles.has('tv/' + season.series) || (variant === 'full' && !qualified)) return null;
      return { ...seasonSummary(season), episodes: qualified ? season.rows.map(episodeData) : null,
        replay_hierarchy: qualified ? 'qualified' : 'unqualified' };
    }
    case 'episode': {
      const episode = episodes.get(id);
      return episode && titles.has('tv/' + episode.series) ? episodeData(episode) : null;
    }
    case 'release_dates':
      return titles.has('movie/' + id) ? releaseDates(id) : null;
    case 'trending': {
      if (!catalog || page !== '1') return null;
      const [media, window] = id.split('/');
      const pool = [...titles].filter((key) => key.startsWith(media + '/')).sort();
      if (window === 'week') pool.reverse();
      const results = pool.slice(0, 20).map((key, index) => ({
        ...metadata(media, key.split('/')[1], false), genre_ids: [18], popularity: 100 - index,
      }));
      return results.length ? { page: 1, total_pages: 1, total_results: results.length, results } : null;
    }
    default:
      return titles.has(family + '/' + id) ? metadata(family, id, variant === 'full') : null;
  }
}

const server = http.createServer((req, res) => {
  const started = performance.now();
  const event = ++nextRequest;
  const url = new URL(req.url, 'http://127.0.0.1');
  const identity = identify(url.pathname);
  const family = identity?.family;
  const id = identity?.id;
  const append = url.searchParams.get('append_to_response');
  const variant = append === null
    ? (family === 'trending' || family === 'release_dates' ? family : 'card') : 'full';
  const language = url.searchParams.get('language');
  const page = url.searchParams.get('page') ?? '1';
  const keys = [...url.searchParams.keys()];
  const supported = req.method === 'GET' && identity &&
    (language === null ? ['tv', 'movie'].includes(family) && append === null : /^[a-z]{2}(?:-[A-Z]{2})?$/.test(language)) &&
    (append === null || append === full[family]) &&
    new Set(keys).size === keys.length &&
    keys.every((key) => ['append_to_response', 'language', 'api_key'].includes(key) || key === 'page' && family === 'trending') &&
    /^[1-9]\d*$/.test(page);
  const result = supported ? payload(family, id, variant, page) : null;
  const status = !supported ? 422 : result === null ? 404 : 200;
  const response = result ?? (status === 404
    ? { status_code: 34, status_message: 'Reference outside replay coverage' }
    : { status_code: 22, status_message: 'Unsupported replay request' });
  const key = [family ?? 'unsupported', id, variant, language ?? 'default', page].join(':');
  const prior = attempts.get(key);
  const occurrence = (prior?.occurrence ?? 0) + 1;
  attempts.set(key, { occurrence, status });
  const record = (status, outcome) => {
    console.log(JSON.stringify({ event, family: family ?? 'unsupported', id: id ?? null, variant,
      language: language && /^[a-z]{2}(?:-[A-Z]{2})?$/.test(language) ? language : null,
      page: family === 'trending' && /^[1-9]\d{0,5}$/.test(page) ? Number(page) : undefined,
      status, outcome, occurrence, previousStatus: prior?.status ?? null,
      durationMs: Number((performance.now() - started).toFixed(3)) }));
  };
  res.once('close', () => {
    if (!res.writableFinished) record(null, 'disconnected');
  });
  const send = () => {
    if (res.destroyed) return;
    res.writeHead(status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
    res.end(JSON.stringify(response), () => record(status, 'responded'));
  };
  if (delayMs) setTimeout(send, delayMs);
  else send();
});

server.listen(port, '127.0.0.1', () =>
  console.log(JSON.stringify({ ready: true, port: server.address().port, fixtures: watchlist.size, delayMs,
    titles: titles.size, seasons: seasons.size, completeSeries: qualifiedSeries.size, completeSeasons: qualifiedSeasons.size,
    episodes: episodes.size, manifestSha256: createHash('sha256').update(manifest).digest('hex') })));
