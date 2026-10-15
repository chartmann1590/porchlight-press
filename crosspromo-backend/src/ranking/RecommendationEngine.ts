import {
  DiscoveredApp,
  RecommendedApp,
  RecommendationQuery,
  SystemConfig
} from '../types';

export const DEFAULT_CONFIG: SystemConfig = {
  enabled: true,
  popularWeight: 0.65,
  randomWeight: 0.35,
  newAppBoostDays: 14,
  defaultLimit: 3,
  maxLimit: 6,
  recommendationTtlSeconds: 14400, // 4 hours
  sourceAppOverrides: {},
  targetExclusions: {},
  manualBoosts: {}
};

/** Recent performance signal for one target app, injected from analytics. */
export interface TargetPerformance {
  impressions: number;
  clicks: number;
}

/** Injectable randomness so ranking behavior is fully testable. */
export interface RandomSource {
  next(): number;
}

/** Default randomness backed by Math.random(). */
export class DefaultRandomSource implements RandomSource {
  next(): number {
    return Math.random();
  }
}

/** Deterministic RNG for tests (xorshift). */
export class SequentialTestRandomSource implements RandomSource {
  private state: number;
  constructor(seed: number = 1) {
    this.state = seed || 1;
  }
  next(): number {
    let x = this.state;
    x ^= x << 13;
    x ^= x >>> 17;
    x ^= x << 5;
    this.state = x >>> 0;
    return this.state / 4294967296;
  }
}

/** Small Beta sampler (Marsaglia & Tsang) for Thompson sampling. */
export class BetaSampler {
  constructor(private random: RandomSource) {}

  sample(alpha: number, beta: number): number {
    if (alpha <= 0 || beta <= 0) {
      return this.random.next();
    }
    const g1 = this.sampleGamma(alpha);
    const g2 = this.sampleGamma(beta);
    const total = g1 + g2;
    return total > 0 ? g1 / total : this.random.next();
  }

  private sampleGamma(shape: number): number {
    if (shape < 1) {
      // Boost sampling: g = Gamma(shape+1) * U^(1/shape)
      const u = Math.max(this.random.next(), 1e-12);
      return this.sampleGamma(shape + 1) * Math.pow(u, 1 / shape);
    }
    const d = shape - 1 / 3;
    const c = 1 / Math.sqrt(9 * d);
    for (let i = 0; i < 128; i++) {
      const x = this.standardNormal();
      const v = Math.pow(1 + c * x, 3);
      if (v <= 0) continue;
      const u = this.random.next();
      const u2 = u * u;
      if (u2 < 1 - 0.0331 * Math.pow(x, 4)) return d * v;
      if (Math.log(u2) < 0.5 * x * x + d * (1 - v + Math.log(v))) return d * v;
    }
    return d; // Extremely unlikely fallback
  }

  private standardNormal(): number {
    // Box-Muller with caching
    if (this.hasSpare) {
      this.hasSpare = false;
      return this.spare;
    }
    let u = 0;
    let v = 0;
    let s = 0;
    do {
      u = this.random.next() * 2 - 1;
      v = this.random.next() * 2 - 1;
      s = u * u + v * v;
    } while (s === 0 || s >= 1);
    const factor = Math.sqrt(-2 * Math.log(s) / s);
    this.spare = v * factor;
    this.hasSpare = true;
    return u * factor;
  }

  private hasSpare = false;
  private spare = 0;
}

/**
 * Generates a ranked, deduplicated, weighted-random list of app recommendations.
 *
 * Selection combines two signals:
 *  1. A popularity prior (Play Store installs/reviews/rating) — cold start.
 *  2. Observed click performance over the trailing analytics window
 *     (Thompson sampling on a Beta(clicks, impressions) posterior built on
 *     top of that prior) — the self-improving feedback loop.
 */
export class RecommendationEngine {
  private config: SystemConfig;
  private random: RandomSource;
  private betaSampler: BetaSampler;

  constructor(config: SystemConfig = DEFAULT_CONFIG, random: RandomSource = new DefaultRandomSource()) {
    this.config = config;
    this.random = random;
    this.betaSampler = new BetaSampler(random);
  }

  /**
   * Generates a ranked, deduplicated, weighted-random list of app recommendations.
   */
  public generateRecommendations(
    catalogApps: DiscoveredApp[],
    query: RecommendationQuery,
    targetPerformance: Record<string, TargetPerformance> = {}
  ): RecommendedApp[] {
    if (!this.config.enabled) {
      return [];
    }

    const sourcePackage = (query.sourcePackage || '').trim().toLowerCase();
    const placement = (query.placement || 'default').trim().toLowerCase();

    // Check source app overrides
    const sourceOverride = this.config.sourceAppOverrides[sourcePackage];
    if (sourceOverride && sourceOverride.enabled === false) {
      return [];
    }

    if (
      sourceOverride?.allowedPlacements &&
      sourceOverride.allowedPlacements.length > 0 &&
      !sourceOverride.allowedPlacements.includes(placement)
    ) {
      return [];
    }

    // Determine limit
    const rawLimit = query.limit || sourceOverride?.maxCards || this.config.defaultLimit;
    const limit = Math.max(1, Math.min(rawLimit, this.config.maxLimit));

    // Normalize exclude set
    const excludeSet = new Set<string>();
    if (sourcePackage) {
      excludeSet.add(sourcePackage); // NEVER promote the source app itself
    }
    if (query.exclude && Array.isArray(query.exclude)) {
      query.exclude.forEach((pkg) => excludeSet.add(pkg.trim().toLowerCase()));
    }

    // Check configured target exclusions for this source package
    const configuredTargetExclusions = this.config.targetExclusions[sourcePackage];
    if (configuredTargetExclusions) {
      configuredTargetExclusions.forEach((pkg) => excludeSet.add(pkg.trim().toLowerCase()));
    }

    // Filter eligible catalog
    const eligibleApps = catalogApps.filter((app) => {
      const pkg = app.packageName.toLowerCase();
      if (excludeSet.has(pkg)) return false;
      if (app.enabledForPromotion === false) return false;
      return true;
    });

    if (eligibleApps.length === 0) {
      return [];
    }

    const now = Date.now();
    const newAppBoostMs = this.config.newAppBoostDays * 24 * 60 * 60 * 1000;

    const selected: RecommendedApp[] = [];
    const pool = [...eligibleApps];

    // Select apps up to requested limit without duplicates.
    while (selected.length < limit && pool.length > 0) {
      const rankPosition = selected.length + 1;

      const sampled = pool.map((app) => this.sampleAppScore(app, targetPerformance[app.packageName], now, newAppBoostMs));
      const bestIndex = argmax(sampled);
      const chosenIndex = bestIndex >= 0 ? bestIndex : 0;
      const chosenApp = pool[chosenIndex];

      const performance = targetPerformance[chosenApp.packageName];
      selected.push({
        packageName: chosenApp.packageName,
        name: chosenApp.name,
        iconUrl: chosenApp.iconUrl,
        shortDescription: chosenApp.shortDescription,
        rating: chosenApp.rating,
        ratingCount: chosenApp.reviewCount,
        installText: chosenApp.installText,
        storeUrl: chosenApp.storeUrl,
        selectionType: this.classifySelection(chosenApp, performance, now, newAppBoostMs),
        rankPosition
      });

      // Remove chosen app from pool so duplicates are impossible.
      pool.splice(chosenIndex, 1);
    }

    return selected;
  }

  /**
   * Thompson sampling with a Beta posterior over click-through quality.
   * The prior strength (alpha0/beta0) comes from the Play popularity score,
   * so brand-new or traffic-poor apps still get a fair shake; observed
   * impressions/clicks sharpen the posterior over the trailing window.
   */
  private sampleAppScore(
    app: DiscoveredApp,
    performance: TargetPerformance | undefined,
    now: number,
    newAppBoostMs: number
  ): number {
    const popularity = Math.max(1.0, app.popularityScore);

    // Prior pseudo-counts, scaled by popularity so high-popularity apps start
    // with a proportionally stronger (but not dominant) belief.
    const alpha0 = 1 + popularity * 0.05;
    const beta0 = 10;

    // Observed data over the trailing window.
    const impressions = performance?.impressions ?? 0;
    const clicks = Math.min(performance?.clicks ?? 0, impressions);

    // Manual boosts and the new-app exploration boost widen the posterior mean
    // via a pseudo-click prior (rather than the old flat 2x weight).
    const manualMultiplier =
      this.config.manualBoosts[app.packageName] || app.promotionMultiplier || 1.0;
    const isNew =
      !isNaN(new Date(app.firstDiscoveredAt).getTime()) &&
      now - new Date(app.firstDiscoveredAt).getTime() < newAppBoostMs;
    const pseudoClicks = manualMultiplier > 1.0 ? (manualMultiplier - 1) * 10 : 0;
    const newAppPseudoClicks = isNew ? 8 : 0;

    const alpha = alpha0 + clicks + pseudoClicks + newAppPseudoClicks;
    const beta = beta0 + impressions - clicks;

    return this.betaSampler.sample(alpha, beta);
  }

  private classifySelection(
    app: DiscoveredApp,
    performance: TargetPerformance | undefined,
    now: number,
    newAppBoostMs: number
  ): 'popular' | 'exploration' | 'boost' {
    const firstSeen = new Date(app.firstDiscoveredAt).getTime();
    const isNew = !isNaN(firstSeen) && now - firstSeen < newAppBoostMs;
    const manualMultiplier =
      this.config.manualBoosts[app.packageName] || app.promotionMultiplier || 1.0;
    if (isNew || manualMultiplier > 1.2) {
      return 'boost';
    }
    const impressions = performance?.impressions ?? 0;
    const clicks = performance?.clicks ?? 0;
    if (impressions >= 20) {
      return clicks / impressions >= 0.08 ? 'popular' : 'exploration';
    }
    return 'popular';
  }
}

function argmax(values: number[]): number {
  let best = -1;
  let bestValue = -Infinity;
  for (let i = 0; i < values.length; i++) {
    if (values[i] > bestValue) {
      bestValue = values[i];
      best = i;
    }
  }
  return best;
}
