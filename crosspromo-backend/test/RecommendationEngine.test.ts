import { describe, it, expect } from 'vitest';
import {
  RecommendationEngine,
  DEFAULT_CONFIG,
  SequentialTestRandomSource,
  TargetPerformance
} from '../src/ranking/RecommendationEngine';
import { DiscoveredApp, SystemConfig } from '../src/types';

describe('RecommendationEngine', () => {
  const sampleApps: DiscoveredApp[] = [
    {
      packageName: 'com.auracast.weather',
      name: 'AuraCast Weather',
      iconUrl: 'https://auracast.png',
      storeUrl: 'https://play.google.com/store/apps/details?id=com.auracast.weather',
      shortDescription: 'Weather app',
      fullDescription: null,
      rating: 4.8,
      reviewCount: 200,
      installText: '10K+',
      estimatedMinimumInstalls: 10000,
      category: 'Weather',
      priceText: 'Free',
      isFree: true,
      developer: 'Hartmann Studios',
      firstDiscoveredAt: new Date(Date.now() - 30 * 24 * 3600 * 1000).toISOString(),
      lastMetadataRefresh: new Date().toISOString(),
      popularityScore: 70,
      enabledForPromotion: true,
      promotionMultiplier: 1.0
    },
    {
      packageName: 'com.charles.nutrisnap',
      name: 'NutriSnap',
      iconUrl: 'https://nutrisnap.png',
      storeUrl: 'https://play.google.com/store/apps/details?id=com.charles.nutrisnap',
      shortDescription: 'Calorie tracker',
      fullDescription: null,
      rating: 4.6,
      reviewCount: 80,
      installText: '1K+',
      estimatedMinimumInstalls: 1000,
      category: 'Health',
      priceText: 'Free',
      isFree: true,
      developer: 'Hartmann Studios',
      firstDiscoveredAt: new Date(Date.now() - 20 * 24 * 3600 * 1000).toISOString(),
      lastMetadataRefresh: new Date().toISOString(),
      popularityScore: 50,
      enabledForPromotion: true,
      promotionMultiplier: 1.0
    },
    {
      packageName: 'com.charles.messenger.v2',
      name: 'TextPilot AI',
      iconUrl: 'https://textpilot.png',
      storeUrl: 'https://play.google.com/store/apps/details?id=com.charles.messenger.v2',
      shortDescription: 'Smart SMS',
      fullDescription: null,
      rating: 4.5,
      reviewCount: 150,
      installText: '5K+',
      estimatedMinimumInstalls: 5000,
      category: 'Communication',
      priceText: 'Free',
      isFree: true,
      developer: 'Hartmann Studios',
      firstDiscoveredAt: new Date(Date.now() - 50 * 24 * 3600 * 1000).toISOString(),
      lastMetadataRefresh: new Date().toISOString(),
      popularityScore: 65,
      enabledForPromotion: true,
      promotionMultiplier: 1.0
    },
    {
      packageName: 'com.chartmann.knightfall',
      name: 'Knightfall Chess',
      iconUrl: 'https://knightfall.png',
      storeUrl: 'https://play.google.com/store/apps/details?id=com.chartmann.knightfall',
      shortDescription: 'Chess coach',
      fullDescription: null,
      rating: 4.9,
      reviewCount: 5,
      installText: '10+',
      estimatedMinimumInstalls: 10,
      category: 'Strategy',
      priceText: 'Free',
      isFree: true,
      developer: 'Hartmann Studios',
      // Newly discovered app! (3 days ago)
      firstDiscoveredAt: new Date(Date.now() - 3 * 24 * 3600 * 1000).toISOString(),
      lastMetadataRefresh: new Date().toISOString(),
      popularityScore: 25,
      enabledForPromotion: true,
      promotionMultiplier: 1.0
    },
    {
      packageName: 'com.charles.paused',
      name: 'Paused App',
      iconUrl: null,
      storeUrl: '',
      shortDescription: null,
      fullDescription: null,
      rating: null,
      reviewCount: null,
      installText: null,
      estimatedMinimumInstalls: 0,
      category: null,
      priceText: 'Free',
      isFree: true,
      developer: 'Hartmann Studios',
      firstDiscoveredAt: new Date().toISOString(),
      lastMetadataRefresh: new Date().toISOString(),
      popularityScore: 20,
      enabledForPromotion: false, // PAUSED!
      promotionMultiplier: 1.0
    }
  ];

  it('strictly excludes the current source app from recommendations', () => {
    const engine = new RecommendationEngine(DEFAULT_CONFIG);
    const recs = engine.generateRecommendations(sampleApps, {
      sourcePackage: 'com.auracast.weather',
      limit: 3
    });

    expect(recs.length).toBe(3);
    expect(recs.some((r) => r.packageName === 'com.auracast.weather')).toBe(false);
  });

  it('never returns duplicate apps in a single recommendation response', () => {
    const engine = new RecommendationEngine(DEFAULT_CONFIG);
    const recs = engine.generateRecommendations(sampleApps, {
      sourcePackage: 'com.auracast.weather',
      limit: 3
    });

    const packageNames = recs.map((r) => r.packageName);
    const uniquePackages = new Set(packageNames);
    expect(uniquePackages.size).toBe(packageNames.length);
  });

  it('filters out paused apps where enabledForPromotion is false', () => {
    const engine = new RecommendationEngine(DEFAULT_CONFIG);
    const recs = engine.generateRecommendations(sampleApps, {
      sourcePackage: 'com.auracast.weather',
      limit: 10
    });

    expect(recs.some((r) => r.packageName === 'com.charles.paused')).toBe(false);
  });

  it('respects client-supplied exclude list', () => {
    const engine = new RecommendationEngine(DEFAULT_CONFIG);
    const recs = engine.generateRecommendations(sampleApps, {
      sourcePackage: 'com.auracast.weather',
      exclude: ['com.charles.nutrisnap'],
      limit: 3
    });

    expect(recs.some((r) => r.packageName === 'com.charles.nutrisnap')).toBe(false);
    expect(recs.some((r) => r.packageName === 'com.auracast.weather')).toBe(false);
  });

  it('respects source app overrides when crosspromo is disabled remotely for an app', () => {
    const configWithOverride: SystemConfig = {
      ...DEFAULT_CONFIG,
      sourceAppOverrides: {
        'com.auracast.weather': { enabled: false }
      }
    };

    const engine = new RecommendationEngine(configWithOverride);
    const recs = engine.generateRecommendations(sampleApps, {
      sourcePackage: 'com.auracast.weather',
      limit: 3
    });

    expect(recs.length).toBe(0);
  });

  it('handles empty catalog or only-source-app catalog gracefully without error', () => {
    const engine = new RecommendationEngine(DEFAULT_CONFIG);
    const emptyRecs = engine.generateRecommendations([], {
      sourcePackage: 'com.auracast.weather'
    });
    expect(emptyRecs).toEqual([]);

    const singleAppCatalog = [sampleApps[0]]; // only com.auracast.weather
    const singleRecs = engine.generateRecommendations(singleAppCatalog, {
      sourcePackage: 'com.auracast.weather'
    });
    expect(singleRecs).toEqual([]);
  });

  it('applies new app exploration boost and assigns selection types', () => {
    const engine = new RecommendationEngine(DEFAULT_CONFIG);
    const recs = engine.generateRecommendations(sampleApps, {
      sourcePackage: 'com.auracast.weather',
      limit: 3
    });

    for (const r of recs) {
      expect(['popular', 'exploration', 'boost']).toContain(r.selectionType);
      expect(r.rankPosition).toBeGreaterThanOrEqual(1);
      expect(r.rankPosition).toBeLessThanOrEqual(3);
    }
  });

  it('feedback loop: strongly-clicked app outranks a higher-popularity app', () => {
    // Deterministic RNG so we can assert on the sampled outcome.
    const engine = new RecommendationEngine(DEFAULT_CONFIG, new SequentialTestRandomSource(42));

    // popularityScore 80 vs 20 — popularity alone would favor appA.
    const highPop: DiscoveredApp = { ...sampleApps[0], packageName: 'com.test.highpop', popularityScore: 80 };
    const lowPop: DiscoveredApp = { ...sampleApps[1], packageName: 'com.test.lowpop', popularityScore: 20 };

    const performance: Record<string, TargetPerformance> = {
      'com.test.highpop': { impressions: 500, clicks: 5 },   // ~1% CTR
      'com.test.lowpop': { impressions: 500, clicks: 150 }   // ~30% CTR
    };

    // Sample many independent recommendations: the low-popularity but
    // high-CTR app must dominate once the bandit has data.
    let lowPopWins = 0;
    const runs = 300;
    for (let i = 0; i < runs; i++) {
      const recs = engine.generateRecommendations(
        [highPop, lowPop],
        { sourcePackage: 'com.other.source', limit: 2 },
        performance
      );
      if (recs[0]?.packageName === 'com.test.lowpop') lowPopWins++;
    }
    expect(lowPopWins).toBeGreaterThan(runs * 0.8);
  });

  it('feedback loop: without performance data, ranking degrades to popularity prior', () => {
    const engine = new RecommendationEngine(DEFAULT_CONFIG, new SequentialTestRandomSource(7));
    const highPop: DiscoveredApp = { ...sampleApps[0], packageName: 'com.test.highpop', popularityScore: 90 };
    const lowPop: DiscoveredApp = { ...sampleApps[1], packageName: 'com.test.lowpop', popularityScore: 10 };

    let highPopWins = 0;
    const runs = 300;
    for (let i = 0; i < runs; i++) {
      const recs = engine.generateRecommendations(
        [highPop, lowPop],
        { sourcePackage: 'com.other.source', limit: 2 },
        {} // no analytics yet
      );
      if (recs[0]?.packageName === 'com.test.highpop') highPopWins++;
    }
    expect(highPopWins).toBeGreaterThan(runs * 0.7);
  });

  it('manual boosts and new apps receive pseudo-click priors', () => {
    const boosted: DiscoveredApp = { ...sampleApps[2], packageName: 'com.test.boosted', promotionMultiplier: 3.0 };
    const plain: DiscoveredApp = { ...sampleApps[1], packageName: 'com.test.plain', promotionMultiplier: 1.0 };
    // Equal popularity, no observed data: the boosted app should win the vast majority.
    boosted.popularityScore = 50;
    plain.popularityScore = 50;

    const engine = new RecommendationEngine(DEFAULT_CONFIG, new SequentialTestRandomSource(11));
    let boostedWins = 0;
    for (let i = 0; i < 300; i++) {
      const recs = engine.generateRecommendations(
        [boosted, plain],
        { sourcePackage: 'com.other.source', limit: 2 }
      );
      if (recs[0]?.packageName === 'com.test.boosted') boostedWins++;
    }
    expect(boostedWins).toBeGreaterThan(240);
  });
});
