import { describe, it, expect } from 'vitest';
import { AnalyticsService, RETAINED_DAYS } from '../src/analytics/AnalyticsService';
import { AnalyticsEvent } from '../src/types';

// In-memory mock for Cloudflare KV
class MockKV implements Partial<KVNamespace> {
  private store = new Map<string, string>();

  async get(key: string, type?: string): Promise<any> {
    const val = this.store.get(key);
    if (!val) return null;
    if (type === 'json') return JSON.parse(val);
    return val;
  }

  async put(key: string, value: string): Promise<void> {
    this.store.set(key, value);
  }
}

describe('AnalyticsService', () => {
  it('records valid events and aggregates impressions, clicks, and CTR correctly', async () => {
    const kv = new MockKV() as unknown as KVNamespace;
    const analytics = new AnalyticsService(kv);

    // Record 10 impressions for com.charles.nutrisnap
    for (let i = 0; i < 10; i++) {
      const impRes = await analytics.recordEvent({
        event: 'promo_impression',
        sourcePackage: 'com.auracast.weather',
        targetPackage: 'com.charles.nutrisnap',
        placement: 'settings',
        sessionId: `sess-${i}`
      });
      expect(impRes.success).toBe(true);
    }

    // Record 2 clicks for com.charles.nutrisnap
    for (let i = 0; i < 2; i++) {
      const clickRes = await analytics.recordEvent({
        event: 'promo_click',
        sourcePackage: 'com.auracast.weather',
        targetPackage: 'com.charles.nutrisnap',
        placement: 'settings',
        sessionId: `sess-${i}`
      });
      expect(clickRes.success).toBe(true);
    }

    // Check summary
    const summary = await analytics.getAnalyticsSummary();
    expect(summary.totalImpressions).toBe(10);
    expect(summary.totalClicks).toBe(2);
    expect(summary.overallCtr).toBe(0.2); // 2 / 10 = 0.20 CTR

    // Check target app stats
    const targetStats = await analytics.getTargetAppStats('com.charles.nutrisnap');
    expect(targetStats.impressions).toBe(10);
    expect(targetStats.clicks).toBe(2);
    expect(targetStats.ctr).toBe(0.2);
  });

  it('rejects invalid or malicious event payloads', async () => {
    const kv = new MockKV() as unknown as KVNamespace;
    const analytics = new AnalyticsService(kv);

    // Invalid event type
    const res1 = await analytics.recordEvent({
      event: 'malicious_event' as any,
      sourcePackage: 'com.auracast.weather',
      targetPackage: 'com.charles.nutrisnap',
      placement: 'settings'
    });
    expect(res1.success).toBe(false);

    // Invalid package name
    const res2 = await analytics.recordEvent({
      event: 'promo_click',
      sourcePackage: 'com.auracast.weather',
      targetPackage: 'DROP TABLE events;--',
      placement: 'settings'
    });
    expect(res2.success).toBe(false);
  });

  it('stores a single versioned day-bucketed document and prunes old days', async () => {
    const kv = new MockKV() as unknown as KVNamespace;
    const analytics = new AnalyticsService(kv);

    const mk = (daysAgo: number, event: AnalyticsEvent['event']): AnalyticsEvent => ({
      event,
      sourcePackage: 'com.auracast.weather',
      targetPackage: 'com.charles.nutrisnap',
      placement: 'settings',
      timestamp: Date.now() - daysAgo * 24 * 60 * 60 * 1000
    });

    // Old events beyond the retention window must be pruned from the doc.
    await analytics.recordEvent(mk(RETAINED_DAYS + 10, 'promo_impression'));
    await analytics.recordEvent(mk(RETAINED_DAYS + 10, 'promo_click'));
    // Recent events count normally.
    await analytics.recordEvent(mk(0, 'promo_impression'));
    await analytics.recordEvent(mk(0, 'promo_impression'));
    await analytics.recordEvent(mk(0, 'promo_click'));

    const raw = JSON.parse(await (kv as any).store.get('stats:summary'));
    expect(Object.keys(raw.daily).length).toBeLessThanOrEqual(RETAINED_DAYS + 1);
    expect(raw.totals.impressions).toBe(3);
    expect(raw.totals.clicks).toBe(2);
    // The ancient events were counted in totals at ingest time but their
    // day buckets fell out of the window; per-target recent history exists.
    const perf = await analytics.getRecentTargetPerformance();
    expect(perf['com.charles.nutrisnap'].impressions).toBe(2);
    expect(perf['com.charles.nutrisnap'].clicks).toBe(1);
  });

  it('getRecentTargetPerformance is empty when no events exist', async () => {
    const kv = new MockKV() as unknown as KVNamespace;
    const analytics = new AnalyticsService(kv);
    expect(await analytics.getRecentTargetPerformance()).toEqual({});
  });
});
