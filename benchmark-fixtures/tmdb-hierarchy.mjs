const positive = (value) => Number.isSafeInteger(value) && value > 0;
const isDay = (value) => typeof value === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(value) &&
  !Number.isNaN(Date.parse(value)) && new Date(value).toISOString().slice(0, 10) === value;

export const isCalendarAirDate = isDay;

function completeSeason(season, asOf) {
  const { rows, releasedCount, lastReleased, knownRuntimeCount, totalKnownRuntime } = season;
  // An explicit null means the source reported no complete runtime aggregate.
  // An omitted field has not established the aggregate's state.
  if (!Object.hasOwn(season, 'totalKnownRuntime') || !positive(releasedCount) || rows.length !== releasedCount || !isDay(lastReleased) ||
      !rows.every((row, index) => row.episode === index + 1 && isDay(row.airDate) && row.airDate <= asOf &&
        (row.runtime == null || positive(row.runtime)))) return false;
  const latest = rows.map(({ airDate }) => airDate).sort().at(-1);
  const runtimes = rows.map(({ runtime }) => runtime).filter(positive);
  return latest === lastReleased && runtimes.length === knownRuntimeCount &&
    (totalKnownRuntime === null ? runtimes.length < rows.length
      : runtimes.length === rows.length && runtimes.reduce((total, runtime) => total + runtime, 0) === totalKnownRuntime);
}

/**
 * Catalogue coordinates prove identity, not release history. Only a separately
 * sourced, explicit roster and episode dates may qualify a reconciliation payload.
 */
export function qualifyHierarchy(catalog, seasons) {
  const qualifiedSeasons = new Set();
  const qualifiedSeries = new Map();
  const references = catalog?.hierarchy;
  if (!references) return { qualifiedSeasons, qualifiedSeries };
  if (!isDay(references.asOf) || !['synthetic-fixture', 'verified-local-source'].includes(references.provenance) ||
      !Array.isArray(references.series)) throw Error('Invalid hierarchy provenance or reference date.');
  const seen = new Set();
  for (const entry of references.series) {
    const { id, regularSeasons, releasedCount, lastReleased } = entry;
    if (!/^[1-9]\d*$/.test(id) || seen.has(id) || !Array.isArray(regularSeasons)) {
      throw Error('Invalid hierarchy roster.');
    }
    seen.add(id);
    if (!regularSeasons.length || !regularSeasons.every(positive) ||
        new Set(regularSeasons).size !== regularSeasons.length) continue;
    const listed = [...regularSeasons].sort((a, b) => a - b);
    const available = [...seasons.values()].filter((season) => season.series === id && season.season > 0);
    const expected = available.map(({ season }) => season).sort((a, b) => a - b);
    // A declared list must account for every known regular-season coordinate.
    const roster = listed.map((number) => seasons.get(id + '/' + number));
    if (listed.join() !== expected.join() ||
        !roster.every((season) => season && completeSeason(season, references.asOf)) ||
        roster.reduce((total, row) => total + row.releasedCount, 0) !== releasedCount ||
        roster.map((row) => row.lastReleased).sort().at(-1) !== lastReleased) continue;
    qualifiedSeries.set(id, roster);
    for (const season of roster) qualifiedSeasons.add(id + '/' + season.season);
  }
  return { qualifiedSeasons, qualifiedSeries };
}
