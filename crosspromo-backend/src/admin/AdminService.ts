import { Catalog, Diagnostics, Env, SystemConfig } from '../types';
import { AnalyticsService } from '../analytics/AnalyticsService';
import { DEFAULT_CONFIG } from '../ranking/RecommendationEngine';

/**
 * Obvious default values that must never silently authorize admin access.
 * If a deployment still has one of these configured, the worker refuses
 * admin requests and the operator must set a real secret.
 */
const FORBIDDEN_DEFAULT_SECRETS = new Set([
  'hartmann-crosspromo-admin-sec-2026',
  'admin',
  'changeme',
  'secret',
  'test'
]);

export class AdminService {
  private kv: KVNamespace;
  private adminSecret: string | undefined;
  private analytics: AnalyticsService;

  constructor(kv: KVNamespace, adminSecret?: string) {
    this.kv = kv;
    this.adminSecret = adminSecret;
    this.analytics = new AnalyticsService(kv);
  }

  /**
   * Verifies if request has authorized admin credentials.
   *
   * Only constant-time-compared Bearer / X-Admin-Key headers are accepted.
   * Query-parameter authentication is intentionally NOT supported: keys in
   * URLs leak into access logs, proxies, and browser history.
   */
  public isAuthorized(request: Request): boolean {
    if (!this.adminSecret) {
      return false; // No secret configured: admin surface is disabled.
    }
    if (FORBIDDEN_DEFAULT_SECRETS.has(this.adminSecret)) {
      return false; // Obvious placeholder secret: refuse rather than allow.
    }

    const authHeader = request.headers.get('Authorization');
    if (authHeader && authHeader.startsWith('Bearer ')) {
      const token = authHeader.substring(7).trim();
      if (timingSafeEqual(token, this.adminSecret)) return true;
    }

    const xAdminKey = request.headers.get('X-Admin-Key');
    if (xAdminKey && timingSafeEqual(xAdminKey.trim(), this.adminSecret)) return true;

    return false;
  }

  /**
   * Builds complete diagnostic report.
   */
  public async getDiagnostics(): Promise<Diagnostics & { catalog?: Catalog }> {
    const catalog = (await this.kv.get('catalog:current', 'json')) as Catalog | null;
    const lastGood = (await this.kv.get('catalog:last_known_good', 'json')) as Catalog | null;
    const config = (await this.kv.get('config:system', 'json')) as SystemConfig | null || DEFAULT_CONFIG;
    const summary = await this.analytics.getAnalyticsSummary();
    const refreshMeta = (await this.kv.get('catalog:meta', 'json')) as {
      lastAttempt?: string;
      lastSuccess?: string;
      source?: string;
      discovered?: number;
      rejected?: number;
      errors?: number;
    } | null || {};

    let cacheStatus: 'fresh' | 'stale' | 'empty' = 'empty';
    if (catalog && catalog.apps.length > 0) {
      const ageHours = (Date.now() - new Date(catalog.generatedAt).getTime()) / (1000 * 60 * 60);
      cacheStatus = ageHours < 12 ? 'fresh' : 'stale';
    }

    // App-level stats
    const appStats: Record<string, { impressions: number; clicks: number; ctr: number }> = {};
    if (catalog?.apps) {
      for (const app of catalog.apps) {
        appStats[app.packageName] = await this.analytics.getTargetAppStats(app.packageName);
      }
    }

    return {
      status: catalog && catalog.apps.length > 0 ? 'ok' : 'degraded',
      catalogApps: catalog ? catalog.apps.length : 0,
      lastRefreshAttempt: refreshMeta.lastAttempt || catalog?.lastRefreshAttempt || null,
      lastSuccessfulRefresh: refreshMeta.lastSuccess || catalog?.lastSuccessfulRefresh || null,
      lastKnownGoodRefresh: lastGood?.lastSuccessfulRefresh || null,
      discoverySource: catalog?.discoverySource || 'none',
      appsDiscovered: refreshMeta.discovered || (catalog ? catalog.apps.length : 0),
      appsRejected: refreshMeta.rejected || 0,
      metadataFailures: refreshMeta.errors || 0,
      cacheStatus,
      totalImpressions: summary.totalImpressions,
      totalClicks: summary.totalClicks,
      overallCtr: summary.overallCtr,
      appStats,
      activeConfig: config,
      catalog: catalog || undefined
    };
  }

  /**
   * Updates remote system configuration.
   */
  public async updateConfig(newConfig: Partial<SystemConfig>): Promise<SystemConfig> {
    const existing = (await this.kv.get('config:system', 'json')) as SystemConfig | null || DEFAULT_CONFIG;
    const merged: SystemConfig = {
      ...existing,
      ...newConfig,
      sourceAppOverrides: {
        ...existing.sourceAppOverrides,
        ...(newConfig.sourceAppOverrides || {})
      },
      targetExclusions: {
        ...existing.targetExclusions,
        ...(newConfig.targetExclusions || {})
      },
      manualBoosts: {
        ...existing.manualBoosts,
        ...(newConfig.manualBoosts || {})
      }
    };

    await this.kv.put('config:system', JSON.stringify(merged));
    return merged;
  }
}

/**
 * Length-independent constant-time string comparison for admin secrets.
 */
function timingSafeEqual(a: string, b: string): boolean {
  const aBytes = new TextEncoder().encode(a);
  const bBytes = new TextEncoder().encode(b);
  const len = Math.max(aBytes.length, bBytes.length);
  let diff = aBytes.length ^ bBytes.length;
  for (let i = 0; i < len; i++) {
    diff |= (aBytes[i] ?? 0) ^ (bBytes[i] ?? 0);
  }
  return diff === 0;
}
