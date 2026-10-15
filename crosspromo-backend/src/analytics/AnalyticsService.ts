import { AggregateStats, AnalyticsEvent, DayStats } from '../types';
import { PlayStoreCatalogProvider } from '../discovery/PlayStoreCatalogProvider';

/**
 * Number of trailing days of per-target history retained in KV.
 * Powers the ranking feedback loop; 30 days covers a full monthly cycle.
 */
export const RETAINED_DAYS = 30;

function emptyDayStats(): DayStats {
  return { impressions: 0, clicks: 0, installs: 0 };
}

function emptyAggregateStats(): AggregateStats {
  return {
    totals: emptyDayStats(),
    daily: {},
    dailyByTarget: {}
  };
}

/**
 * Day-bucketed aggregate stats. Kept deliberately small so they can be
 * persisted and re-read from KV with bounded cost.
 */
export class AnalyticsService {
  private kv: KVNamespace;

  constructor(kv: KVNamespace) {
    this.kv = kv;
  }

  /**
   * Validates and records an incoming client event.
   * Protects against spam, malformed payloads, and injection.
   */
  public async recordEvent(event: AnalyticsEvent, clientIp?: string): Promise<{ success: boolean; error?: string }> {
    // Basic validation
    if (!event || typeof event !== 'object') {
      return { success: false, error: 'Invalid event payload' };
    }

    if (!['promo_impression', 'promo_click', 'crosspromo_install'].includes(event.event)) {
      return { success: false, error: `Unsupported event type: ${event.event}` };
    }

    if (!PlayStoreCatalogProvider.isValidPackageName(event.targetPackage)) {
      return { success: false, error: 'Invalid targetPackage format' };
    }

    if (event.sourcePackage && !PlayStoreCatalogProvider.isValidPackageName(event.sourcePackage)) {
      return { success: false, error: 'Invalid sourcePackage format' };
    }

    // Rate limit check per IP (max 120 events per minute per IP)
    if (clientIp) {
      const ipKey = `ratelimit:events:${clientIp}`;
      const currentCountStr = await this.kv.get(ipKey);
      const currentCount = currentCountStr ? parseInt(currentCountStr, 10) : 0;
      if (currentCount > 120) {
        return { success: false, error: 'Rate limit exceeded' };
      }
      await this.kv.put(ipKey, (currentCount + 1).toString(), { expirationTtl: 60 });
    }

    const timestamp = typeof event.timestamp === 'number' ? event.timestamp : Date.now();
    const date = new Date(timestamp).toISOString().split('T')[0];

    // Sanitize values
    const safeEvent: AnalyticsEvent = {
      event: event.event,
      sourcePackage: (event.sourcePackage || 'unknown').slice(0, 100),
      targetPackage: event.targetPackage.slice(0, 100),
      placement: (event.placement || 'default').slice(0, 50),
      rankPosition: typeof event.rankPosition === 'number' ? event.rankPosition : undefined,
      selectionType: event.selectionType ? event.selectionType.slice(0, 30) : undefined,
      sessionId: event.sessionId ? event.sessionId.slice(0, 64) : undefined,
      recommendationRequestId: event.recommendationRequestId ? event.recommendationRequestId.slice(0, 64) : undefined,
      sdkVersion: event.sdkVersion ? event.sdkVersion.slice(0, 20) : undefined,
      timestamp
    };

    try {
      await this.updateAggregateStats(safeEvent, date);
    } catch (err) {
      // Analytics aggregation must never fail the event ingestion path.
      console.error('Failed to update aggregate stats:', err);
    }

    return { success: true };
  }

  /**
   * Updates day-bucketed aggregates in KV. Writes a single versioned document
   * per event instead of the previous per-counter read-modify-write, so the
   * write amplification is O(1) KV ops per event and stale/racing writers
   * cannot corrupt totals silently.
   *
   * KV is eventually consistent: concurrent events still race and can lose
   * individual increments. That is acceptable for ranking signals; swap this
   * for Workers Analytics Engine or D1 if exact counts ever matter.
   */
  private async updateAggregateStats(event: AnalyticsEvent, date: string): Promise<void> {
    const raw = await this.kv.get('stats:summary', 'json');
    const stats: AggregateStats = (raw as AggregateStats | null) ?? emptyAggregateStats();

    // Defensive defaults in case a stored document predates a schema field.
    if (!stats.totals) stats.totals = emptyDayStats();
    if (!stats.daily) stats.daily = {};
    if (!stats.dailyByTarget) stats.dailyByTarget = {};

    let day = stats.daily[date];
    if (!day) {
      day = emptyDayStats();
      stats.daily[date] = day;
    }

    if (event.event === 'promo_impression') {
      stats.totals.impressions++;
      day.impressions++;
    } else if (event.event === 'promo_click') {
      stats.totals.clicks++;
      day.clicks++;
    } else if (event.event === 'crosspromo_install') {
      stats.totals.installs++;
      day.installs++;
    }

    // Per-target daily counters feed the recommendation feedback loop.
    let targetDays = stats.dailyByTarget[event.targetPackage];
    if (!targetDays) {
      targetDays = {};
      stats.dailyByTarget[event.targetPackage] = targetDays;
    }
    let targetDay = targetDays[date];
    if (!targetDay) {
      targetDay = emptyDayStats();
      targetDays[date] = targetDay;
    }
    targetDay[event.event === 'promo_impression' ? 'impressions' : event.event === 'promo_click' ? 'clicks' : 'installs']++;

    // Prune trailing-day history so the document stays bounded.
    pruneOldDays(stats, date);

    await this.kv.put('stats:summary', JSON.stringify(stats));
  }

  /**
   * Retrieves summary diagnostics for admin dashboard.
   */
  public async getAnalyticsSummary(): Promise<{
    totalImpressions: number;
    totalClicks: number;
    totalInstalls: number;
    overallCtr: number;
  }> {
    const summary = (await this.kv.get('stats:summary', 'json')) as AggregateStats | null;

    const totals: DayStats = summary?.totals ?? { impressions: 0, clicks: 0, installs: 0 };
    const impressions = totals.impressions;
    const clicks = totals.clicks;
    const installs = totals.installs;

    const ctr = impressions > 0
      ? Math.round((clicks / impressions) * 10000) / 10000
      : 0;

    return {
      totalImpressions: impressions,
      totalClicks: clicks,
      totalInstalls: installs,
      overallCtr: ctr
    };
  }

  /**
   * Gets stats for a specific target app.
   */
  public async getTargetAppStats(packageName: string): Promise<{
    impressions: number;
    clicks: number;
    ctr: number;
  }> {
    const summary = (await this.kv.get('stats:summary', 'json')) as AggregateStats | null;
    const targetDays = summary?.dailyByTarget?.[packageName];
    if (!targetDays) {
      return { impressions: 0, clicks: 0, ctr: 0 };
    }

    let impressions = 0;
    let clicks = 0;
    for (const day of Object.values(targetDays)) {
      impressions += day.impressions;
      clicks += day.clicks;
    }

    const ctr = impressions > 0
      ? Math.round((clicks / impressions) * 10000) / 10000
      : 0;

    return { impressions, clicks, ctr };
  }

  /**
   * Aggregated (target, placement)-independent recent performance used by the
   * ranking feedback loop: trailing-window impressions and clicks per target.
   */
  public async getRecentTargetPerformance(): Promise<Record<string, { impressions: number; clicks: number }>> {
    const summary = (await this.kv.get('stats:summary', 'json')) as AggregateStats | null;
    const result: Record<string, { impressions: number; clicks: number }> = {};

    if (!summary?.dailyByTarget) {
      return result;
    }

    for (const [targetPackage, days] of Object.entries(summary.dailyByTarget)) {
      let impressions = 0;
      let clicks = 0;
      for (const day of Object.values(days)) {
        impressions += day.impressions;
        clicks += day.clicks;
      }
      result[targetPackage] = { impressions, clicks };
    }

    return result;
  }
}

/**
 * Drops daily buckets older than RETAINED_DAYS behind the newest date seen.
 */
function pruneOldDays(stats: AggregateStats, newestDate: string): void {
  const newest = new Date(newestDate).getTime();
  const cutoff = newest - RETAINED_DAYS * 24 * 60 * 60 * 1000;

  for (const date of Object.keys(stats.daily)) {
    if (new Date(date).getTime() < cutoff) {
      delete stats.daily[date];
    }
  }

  for (const targetDays of Object.values(stats.dailyByTarget)) {
    for (const date of Object.keys(targetDays)) {
      if (new Date(date).getTime() < cutoff) {
        delete targetDays[date];
      }
    }
  }

  // Drop targets with no remaining history.
  for (const targetPackage of Object.keys(stats.dailyByTarget)) {
    if (Object.keys(stats.dailyByTarget[targetPackage]).length === 0) {
      delete stats.dailyByTarget[targetPackage];
    }
  }
}
