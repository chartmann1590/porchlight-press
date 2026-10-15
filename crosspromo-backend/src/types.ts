/**
 * Core type definitions for Hartmann Studios Dynamic Cross-Promotion Platform
 */

export interface DiscoveredApp {
  packageName: string;
  name: string;
  iconUrl: string | null;
  storeUrl: string;
  shortDescription: string | null;
  fullDescription: string | null;
  rating: number | null;
  reviewCount: number | null;
  installText: string | null;
  estimatedMinimumInstalls: number;
  category: string | null;
  priceText: string | null;
  isFree: boolean;
  developer: string | null;
  firstDiscoveredAt: string;
  lastMetadataRefresh: string;
  popularityScore: number;
  enabledForPromotion: boolean;
  promotionMultiplier: number;
  recentImpressions?: number;
}

export interface Catalog {
  version: number;
  generatedAt: string;
  lastRefreshAttempt: string;
  lastSuccessfulRefresh: string;
  discoverySource: string;
  apps: DiscoveredApp[];
}

export interface RecommendedApp {
  packageName: string;
  name: string;
  iconUrl: string | null;
  shortDescription: string | null;
  rating: number | null;
  ratingCount: number | null;
  installText: string | null;
  storeUrl: string;
  selectionType: 'popular' | 'exploration' | 'boost';
  rankPosition: number;
}

export interface RecommendationResponse {
  version: number;
  requestId: string;
  generatedAt: string;
  expiresAt: string;
  apps: RecommendedApp[];
}

export interface RecommendationQuery {
  sourcePackage?: string;
  placement?: string;
  limit?: number;
  sessionId?: string;
  exclude?: string[];
  locale?: string;
}

export interface AnalyticsEvent {
  event: 'promo_impression' | 'promo_click' | 'crosspromo_install';
  sourcePackage: string;
  targetPackage: string;
  placement: string;
  rankPosition?: number;
  selectionType?: string;
  sessionId?: string;
  recommendationRequestId?: string;
  sdkVersion?: string;
  timestamp?: number;
}

/**
 * Day-bucketed aggregate stats. Kept deliberately small so they can be
 * persisted and re-read from KV with bounded cost.
 */
export interface DayStats {
  impressions: number;
  clicks: number;
  installs: number;
}

/**
 * Analytics storage document. Totals are kept for the admin dashboard; the
 * daily map powers the ranking feedback loop (recent-performance signals).
 * Trailing-day entries are pruned on write.
 */
export interface AggregateStats {
  totals: DayStats;
  daily: Record<string, DayStats>; // YYYY-MM-DD -> counters
  dailyByTarget: Record<string, Record<string, DayStats>>; // targetPackage -> date -> counters
}

export interface SystemConfig {
  enabled: boolean;
  popularWeight: number;
  randomWeight: number;
  newAppBoostDays: number;
  defaultLimit: number;
  maxLimit: number;
  recommendationTtlSeconds: number;
  sourceAppOverrides: Record<string, {
    enabled?: boolean;
    maxCards?: number;
    allowedPlacements?: string[];
  }>;
  targetExclusions: Record<string, string[]>; // sourcePackage -> array of targetPackages to exclude
  manualBoosts: Record<string, number>; // targetPackage -> multiplier
}

export interface Diagnostics {
  status: 'ok' | 'degraded' | 'error';
  catalogApps: number;
  lastRefreshAttempt: string | null;
  lastSuccessfulRefresh: string | null;
  lastKnownGoodRefresh: string | null;
  discoverySource: string;
  appsDiscovered: number;
  appsRejected: number;
  metadataFailures: number;
  cacheStatus: 'fresh' | 'stale' | 'empty';
  totalImpressions: number;
  totalClicks: number;
  overallCtr: number;
  appStats?: Record<string, { impressions: number; clicks: number; ctr: number }>;
  activeConfig?: SystemConfig;
}

export interface Env {
  CROSS_PROMO_KV: KVNamespace;
  DEVELOPER_ID?: string;
  PLAY_STORE_DEV_URL?: string;
  ADMIN_SECRET?: string;
  HEALTHCHECK_PING_URL?: string;
  ENVIRONMENT?: string;
}
