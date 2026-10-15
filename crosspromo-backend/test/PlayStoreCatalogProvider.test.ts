import { describe, it, expect } from 'vitest';
import * as fs from 'fs';
import * as path from 'path';
import { PlayStoreCatalogProvider } from '../src/discovery/PlayStoreCatalogProvider';
import { Catalog, DiscoveredApp } from '../src/types';

describe('PlayStoreCatalogProvider', () => {
  it('validates canonical Android package names', () => {
    expect(PlayStoreCatalogProvider.isValidPackageName('com.auracast.weather')).toBe(true);
    expect(PlayStoreCatalogProvider.isValidPackageName('com.charles.nutrisnap')).toBe(true);
    expect(PlayStoreCatalogProvider.isValidPackageName('com.charles.messenger.v2')).toBe(true);
    expect(PlayStoreCatalogProvider.isValidPackageName('com.chartmann.knightfall')).toBe(true);

    // Invalid package names
    expect(PlayStoreCatalogProvider.isValidPackageName('')).toBe(false);
    expect(PlayStoreCatalogProvider.isValidPackageName('not_a_package')).toBe(false);
    expect(PlayStoreCatalogProvider.isValidPackageName('123.com.test')).toBe(false);
    expect(PlayStoreCatalogProvider.isValidPackageName('com.test..app')).toBe(false);
    expect(PlayStoreCatalogProvider.isValidPackageName('/store/apps/details')).toBe(false);
  });

  it('correctly parses install count strings', () => {
    expect(PlayStoreCatalogProvider.parseInstallCount('5+')).toBe(5);
    expect(PlayStoreCatalogProvider.parseInstallCount('100+')).toBe(100);
    expect(PlayStoreCatalogProvider.parseInstallCount('1,000+')).toBe(1000);
    expect(PlayStoreCatalogProvider.parseInstallCount('5,000+')).toBe(5000);
    expect(PlayStoreCatalogProvider.parseInstallCount('50K+')).toBe(50000);
    expect(PlayStoreCatalogProvider.parseInstallCount('100K+')).toBe(100000);
    expect(PlayStoreCatalogProvider.parseInstallCount('1M+')).toBe(1000000);
    expect(PlayStoreCatalogProvider.parseInstallCount(null)).toBe(0);
    expect(PlayStoreCatalogProvider.parseInstallCount('unknown')).toBe(0);
  });

  it('computes popularity score gracefully with complete or partial metadata', () => {
    // High installs + ratings
    const score1 = PlayStoreCatalogProvider.computePopularityScore(100000, 2000, 4.7, 0.05);
    expect(score1).toBeGreaterThan(60);

    // Small installs but decent rating
    const score2 = PlayStoreCatalogProvider.computePopularityScore(100, 10, 4.5, 0.02);
    expect(score2).toBeGreaterThan(20);
    expect(score2).toBeLessThan(score1);

    // No installs, only rating
    const score3 = PlayStoreCatalogProvider.computePopularityScore(0, null, 4.8);
    expect(score3).toBeGreaterThan(0);

    // Completely empty metadata
    const scoreDefault = PlayStoreCatalogProvider.computePopularityScore(0, null, null);
    expect(scoreDefault).toBe(10.0);
  });

  it('extracts real apps dynamically from Play Store developer HTML fixture without hardcoding', async () => {
    const fixturePath = path.join(__dirname, 'fixtures', 'play_dev_page.html');
    expect(fs.existsSync(fixturePath)).toBe(true);

    const html = fs.readFileSync(fixturePath, 'utf8');
    const provider = new PlayStoreCatalogProvider('Hartmann Studios');
    const result = await provider.discoverApps(html);

    expect(result.apps.length).toBeGreaterThanOrEqual(15);
    expect(result.apps.length).toBe(20); // Hartmann Studios currently has 20 published apps

    // Verify key fields are extracted
    const auracast = result.apps.find((a) => a.packageName === 'com.auracast.weather');
    expect(auracast).toBeDefined();
    expect(auracast?.name).toBe('AuraCast Weather');
    expect(auracast?.category).toBe('Weather');
    expect(auracast?.iconUrl).toContain('https://play-lh.googleusercontent.com');
    expect(auracast?.developer).toBe('Hartmann Studios');
    expect(auracast?.storeUrl).toBe('https://play.google.com/store/apps/details?id=com.auracast.weather');

    const nutrisnap = result.apps.find((a) => a.packageName === 'com.charles.nutrisnap');
    expect(nutrisnap).toBeDefined();
    expect(nutrisnap?.name).toContain('NutriSnap');
    expect(nutrisnap?.category).toBe('Health & Fitness');
  });

  it('enforces safety rules: blocks overwrite when 0 apps or sudden drops occur', () => {
    const existingCatalog: Catalog = {
      version: 1,
      generatedAt: new Date().toISOString(),
      lastRefreshAttempt: new Date().toISOString(),
      lastSuccessfulRefresh: new Date().toISOString(),
      discoverySource: 'test',
      apps: Array.from({ length: 20 }, (_, i) => ({
        packageName: `com.hartmann.app${i}`,
        name: `App ${i}`,
        iconUrl: null,
        storeUrl: `https://play.google.com/store/apps/details?id=com.hartmann.app${i}`,
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
        popularityScore: 10,
        enabledForPromotion: true,
        promotionMultiplier: 1.0
      }))
    };

    // 0 apps discovered: REJECT
    const checkEmpty = PlayStoreCatalogProvider.validateCatalogRefresh([], existingCatalog);
    expect(checkEmpty.isValid).toBe(false);

    // Sudden drop from 20 apps to 1 app: REJECT
    const checkDrop = PlayStoreCatalogProvider.validateCatalogRefresh([existingCatalog.apps[0]], existingCatalog);
    expect(checkDrop.isValid).toBe(false);

    // Normal refresh with 18 apps (e.g. 1 app removed legitimately): ACCEPT
    const checkValid = PlayStoreCatalogProvider.validateCatalogRefresh(existingCatalog.apps.slice(0, 18), existingCatalog);
    expect(checkValid.isValid).toBe(true);
  });

  it('merges new discovery with existing catalog while preserving discovery dates and custom multipliers', () => {
    const oldDate = '2026-01-01T00:00:00.000Z';
    const existingCatalog: Catalog = {
      version: 1,
      generatedAt: oldDate,
      lastRefreshAttempt: oldDate,
      lastSuccessfulRefresh: oldDate,
      discoverySource: 'test',
      apps: [
        {
          packageName: 'com.auracast.weather',
          name: 'AuraCast Weather Old',
          iconUrl: null,
          storeUrl: '',
          shortDescription: null,
          fullDescription: null,
          rating: 4.5,
          reviewCount: 10,
          installText: '5+',
          estimatedMinimumInstalls: 5,
          category: 'Weather',
          priceText: 'Free',
          isFree: true,
          developer: 'Hartmann Studios',
          firstDiscoveredAt: oldDate,
          lastMetadataRefresh: oldDate,
          popularityScore: 20,
          enabledForPromotion: true,
          promotionMultiplier: 2.5 // Custom manual boost
        }
      ]
    };

    const newDiscovered: DiscoveredApp = {
      packageName: 'com.auracast.weather',
      name: 'AuraCast Weather New Name',
      iconUrl: 'https://new-icon.png',
      storeUrl: 'https://play.google.com/store/apps/details?id=com.auracast.weather',
      shortDescription: 'New desc',
      fullDescription: 'New full desc',
      rating: 4.8,
      reviewCount: 25,
      installText: '100+',
      estimatedMinimumInstalls: 100,
      category: 'Weather',
      priceText: 'Free',
      isFree: true,
      developer: 'Hartmann Studios',
      firstDiscoveredAt: new Date().toISOString(),
      lastMetadataRefresh: new Date().toISOString(),
      popularityScore: 35,
      enabledForPromotion: true,
      promotionMultiplier: 1.0
    };

    const merged = PlayStoreCatalogProvider.mergeWithExistingCatalog([newDiscovered], existingCatalog);
    expect(merged.length).toBe(1);
    expect(merged[0].firstDiscoveredAt).toBe(oldDate); // Preserved!
    expect(merged[0].promotionMultiplier).toBe(2.5); // Preserved!
    expect(merged[0].name).toBe('AuraCast Weather New Name'); // Updated!
    expect(merged[0].installText).toBe('100+'); // Updated!
  });
});
