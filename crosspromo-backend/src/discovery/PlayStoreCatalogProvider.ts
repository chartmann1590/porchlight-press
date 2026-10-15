import { DiscoveredApp, Catalog } from '../types';

/**
 * Defensive, resilient Google Play Store catalog discovery provider.
 * Extracts, validates, normalizes, and scores Android apps from the public developer page.
 */
export class PlayStoreCatalogProvider {
  private developerId: string;
  private developerPageUrl: string;

  constructor(
    developerId = 'Hartmann Studios',
    developerPageUrl = 'https://play.google.com/store/apps/developer?id=Hartmann+Studios'
  ) {
    this.developerId = developerId;
    this.developerPageUrl = developerPageUrl;
  }

  /**
   * Validates canonical Android package name format:
   * e.g., com.auracast.weather, com.charles.nutrisnap
   */
  public static isValidPackageName(pkg: string): boolean {
    if (!pkg || typeof pkg !== 'string' || pkg.length < 4 || pkg.length > 150) {
      return false;
    }
    // Android package name convention: dot-separated identifiers, starting with letter
    const regex = /^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+$/;
    return regex.test(pkg);
  }

  /**
   * Parses install string into minimum estimated install integer.
   * e.g., "5+" -> 5, "100+" -> 100, "1,000+" -> 1000, "10K+" -> 10000, "1M+" -> 1000000
   */
  public static parseInstallCount(text: string | null | undefined): number {
    if (!text) return 0;
    const clean = text.replace(/,/g, '').trim().toUpperCase();
    const match = clean.match(/^([\d.]+)\s*([KMB])?\+?$/);
    if (!match) return 0;

    const num = parseFloat(match[1]);
    const multiplier = match[2];
    if (isNaN(num)) return 0;

    switch (multiplier) {
      case 'K':
        return Math.round(num * 1000);
      case 'M':
        return Math.round(num * 1000000);
      case 'B':
        return Math.round(num * 1000000000);
      default:
        return Math.round(num);
    }
  }

  /**
   * Computes normalized popularity score (0 - 100) using trustworthy public metrics.
   * Gracefully degrades if certain fields are absent.
   */
  public static computePopularityScore(
    installs: number,
    reviewCount: number | null,
    rating: number | null,
    ctr: number = 0
  ): number {
    let score = 0;
    let weightSum = 0;

    // Installs score (0 - 100 scale, logarithmic based on Android tiers: 10, 100, 1k, 10k, 100k, 1M)
    if (installs > 0) {
      const installScore = Math.min(100, Math.max(0, Math.log10(installs) * 20));
      score += installScore * 0.50;
      weightSum += 0.50;
    }

    // Review count score (0 - 100 scale, log-scaled)
    if (reviewCount !== null && reviewCount > 0) {
      const reviewScore = Math.min(100, Math.max(0, Math.log10(reviewCount) * 25));
      score += reviewScore * 0.25;
      weightSum += 0.25;
    }

    // Rating score (0 - 100 scale, 5.0 = 100)
    if (rating !== null && rating > 0) {
      const ratingScore = Math.min(100, Math.max(0, (rating / 5.0) * 100));
      score += ratingScore * 0.15;
      weightSum += 0.15;
    }

    // CTR promo performance score (0 - 100 scale)
    if (ctr > 0) {
      const ctrScore = Math.min(100, Math.max(0, ctr * 1000));
      score += ctrScore * 0.10;
      weightSum += 0.10;
    }

    // If no signals present at all, assign modest default base score
    if (weightSum === 0) {
      return 10.0;
    }

    // Re-normalize according to available weight
    return Math.round((score / weightSum) * 10) / 10;
  }

  /**
   * Primary parser: extracts apps from Google Play's AF_initDataCallback structures.
   * Google Play encodes app listings in `AF_initDataCallback({key: 'ds:...', ... data: [...]})`
   */
  public parseInitDataCallbacks(html: string): DiscoveredApp[] {
    const appsMap = new Map<string, DiscoveredApp>();
    const nowIso = new Date().toISOString();

    const regex = /AF_initDataCallback\s*\(\s*\{key:\s*'([^']+)'(?:,\s*hash:\s*'[^']*')?,\s*data:\s*([\s\S]*?),\s*sideChannel:\s*\{/g;
    let m: RegExpExecArray | null;

    while ((m = regex.exec(html)) !== null) {
      const key = m[1];
      const rawData = m[2];

      try {
        const data = JSON.parse(rawData);
        // Look for app clusters in the parsed JSON
        this.extractAppsFromJsonTree(data, appsMap, nowIso);
      } catch {
        // Continue searching other callbacks
      }
    }

    return Array.from(appsMap.values());
  }

  /**
   * Recursively inspects parsed callback JSON arrays looking for Google Play app cards.
   */
  private extractAppsFromJsonTree(
    node: any,
    appsMap: Map<string, DiscoveredApp>,
    nowIso: string
  ): void {
    if (!node || typeof node !== 'object') return;

    if (Array.isArray(node)) {
      // Check if this array matches the standard Google Play app card structure:
      // entry[0] is array where:
      //   entry[0][0][0] is package name (e.g. "com.auracast.weather")
      //   entry[0][3] is app title
      //   entry[0][1][3][2] is icon URL
      //   entry[0][5] is category
      //   entry[0][13][1] is description
      //   entry[0][14] is developer name
      //   entry[0][15] is install text (e.g. "100+", "1,000+")
      const candidateApp = Array.isArray(node[0]) ? node[0] : node;

      if (
        Array.isArray(candidateApp) &&
        candidateApp.length >= 4 &&
        Array.isArray(candidateApp[0]) &&
        typeof candidateApp[0][0] === 'string' &&
        PlayStoreCatalogProvider.isValidPackageName(candidateApp[0][0]) &&
        typeof candidateApp[3] === 'string'
      ) {
        const packageName = candidateApp[0][0].trim();
        if (!appsMap.has(packageName)) {
          const name = candidateApp[3].trim();
          const iconUrl = candidateApp[1]?.[3]?.[2] || null;
          const category = typeof candidateApp[5] === 'string' ? candidateApp[5] : null;
          const fullDesc = typeof candidateApp[13]?.[1] === 'string' ? candidateApp[13][1] : null;
          const shortDesc = fullDesc ? this.formatShortDescription(fullDesc) : null;
          const developer = typeof candidateApp[14] === 'string' ? candidateApp[14] : this.developerId;
          const installText = typeof candidateApp[15] === 'string' ? candidateApp[15] : null;
          const minInstalls = PlayStoreCatalogProvider.parseInstallCount(installText);

          // Find rating if present in item
          let rating: number | null = null;
          let reviewCount: number | null = null;

          for (let i = 0; i < candidateApp.length; i++) {
            const v = candidateApp[i];
            if (typeof v === 'number' && v >= 1.0 && v <= 5.0) {
              rating = Math.round(v * 10) / 10;
            } else if (Array.isArray(v) && v.length >= 2 && typeof v[0] === 'number' && v[0] <= 5.0) {
              rating = Math.round(v[0] * 10) / 10;
              if (typeof v[1] === 'number') reviewCount = v[1];
            }
          }

          const popularityScore = PlayStoreCatalogProvider.computePopularityScore(
            minInstalls,
            reviewCount,
            rating
          );

          appsMap.set(packageName, {
            packageName,
            name,
            iconUrl,
            storeUrl: `https://play.google.com/store/apps/details?id=${packageName}`,
            shortDescription: shortDesc,
            fullDescription: fullDesc,
            rating,
            reviewCount,
            installText,
            estimatedMinimumInstalls: minInstalls,
            category,
            priceText: 'Free',
            isFree: true,
            developer,
            firstDiscoveredAt: nowIso,
            lastMetadataRefresh: nowIso,
            popularityScore,
            enabledForPromotion: true,
            promotionMultiplier: 1.0,
            recentImpressions: 0
          });
        }
      }

      // Continue traversing children
      for (const item of node) {
        if (typeof item === 'object') {
          this.extractAppsFromJsonTree(item, appsMap, nowIso);
        }
      }
    } else {
      for (const key of Object.keys(node)) {
        this.extractAppsFromJsonTree(node[key], appsMap, nowIso);
      }
    }
  }

  /**
   * Defensive fallback HTML/DOM parser:
   * Scans HTML for canonical Play Store detail links `/store/apps/details?id=...`
   * and nearby title/icon/description tags.
   */
  public parseHtmlFallback(html: string): DiscoveredApp[] {
    const appsMap = new Map<string, DiscoveredApp>();
    const nowIso = new Date().toISOString();

    const linkRegex = /\/store\/apps\/details\?id=([a-zA-Z0-9._]+)/g;
    let match: RegExpExecArray | null;

    while ((match = linkRegex.exec(html)) !== null) {
      const packageName = match[1];
      if (!PlayStoreCatalogProvider.isValidPackageName(packageName)) continue;
      if (appsMap.has(packageName)) continue;

      // Extract window around match
      const start = Math.max(0, match.index - 500);
      const end = Math.min(html.length, match.index + 2500);
      const snippet = html.substring(start, end);

      // Extract title from Play button aria-label or span class
      let name = packageName;
      const playBtnMatch = snippet.match(/aria-label="Play ([^"]+)"/i);
      const titleSpanMatch = snippet.match(/<span class="DdYX5">([^<]+)<\/span>/);
      const imgAltMatch = snippet.match(/<img [^>]*alt="([^"]+)"[^>]*>/);

      if (playBtnMatch && playBtnMatch[1]) {
        name = playBtnMatch[1].trim();
      } else if (titleSpanMatch && titleSpanMatch[1]) {
        name = titleSpanMatch[1].trim();
      } else if (imgAltMatch && imgAltMatch[1] && !imgAltMatch[1].includes('Screenshot') && !imgAltMatch[1].includes('Thumbnail')) {
        name = imgAltMatch[1].trim();
      }

      // Extract icon URL
      let iconUrl: string | null = null;
      const iconMatch = snippet.match(/https:\/\/play-lh\.googleusercontent\.com\/[a-zA-Z0-9_\-=]+/);
      if (iconMatch) {
        iconUrl = iconMatch[0];
      }

      appsMap.set(packageName, {
        packageName,
        name,
        iconUrl,
        storeUrl: `https://play.google.com/store/apps/details?id=${packageName}`,
        shortDescription: null,
        fullDescription: null,
        rating: null,
        reviewCount: null,
        installText: null,
        estimatedMinimumInstalls: 0,
        category: null,
        priceText: 'Free',
        isFree: true,
        developer: this.developerId,
        firstDiscoveredAt: nowIso,
        lastMetadataRefresh: nowIso,
        popularityScore: 15.0,
        enabledForPromotion: true,
        promotionMultiplier: 1.0,
        recentImpressions: 0
      });
    }

    return Array.from(appsMap.values());
  }

  /**
   * Cleans and truncates full description into a clean 1-2 sentence promo description.
   */
  private formatShortDescription(htmlText: string): string {
    const text = htmlText
      .replace(/<[^>]+>/g, ' ')
      .replace(/&amp;/g, '&')
      .replace(/&#39;/g, "'")
      .replace(/&quot;/g, '"')
      .replace(/&lt;/g, '<')
      .replace(/&gt;/g, '>')
      .replace(/\s+/g, ' ')
      .trim();

    if (text.length <= 130) return text;

    // Find sentence or word break before 130 characters
    const truncated = text.substring(0, 130);
    const lastPeriod = truncated.lastIndexOf('.');
    if (lastPeriod > 40) {
      return truncated.substring(0, lastPeriod + 1);
    }
    const lastSpace = truncated.lastIndexOf(' ');
    if (lastSpace > 30) {
      return truncated.substring(0, lastSpace) + '...';
    }
    return truncated + '...';
  }

  /**
   * Main discovery entrypoint:
   * Fetches developer page, tries primary parser, falls back to DOM scanner if needed.
   */
  public async discoverApps(customHtml?: string): Promise<{
    apps: DiscoveredApp[];
    strategy: 'callback' | 'html_fallback';
    rejectedCount: number;
  }> {
    let html = customHtml;

    if (!html) {
      const response = await fetch(this.developerPageUrl, {
        headers: {
          'User-Agent':
            'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36',
          'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
          'Accept-Language': 'en-US,en;q=0.9'
        }
      });

      if (!response.ok) {
        throw new Error(`Failed to fetch Google Play developer page: HTTP ${response.status}`);
      }
      html = await response.text();
    }

    // Strategy 1: Data Callbacks
    let apps = this.parseInitDataCallbacks(html);
    let strategy: 'callback' | 'html_fallback' = 'callback';

    // Strategy 2: Defensive HTML Fallback
    if (apps.length === 0) {
      apps = this.parseHtmlFallback(html);
      strategy = 'html_fallback';
    }

    // Filter and sanitize: must be valid package name
    const validApps: DiscoveredApp[] = [];
    let rejectedCount = 0;

    for (const app of apps) {
      if (PlayStoreCatalogProvider.isValidPackageName(app.packageName)) {
        validApps.push(app);
      } else {
        rejectedCount++;
      }
    }

    return {
      apps: validApps,
      strategy,
      rejectedCount
    };
  }

  /**
   * Validates refreshed catalog against previous catalog to prevent accidental data loss.
   * Safety rules:
   * 1. If 0 apps discovered: REJECT
   * 2. If previous catalog had >= 5 apps and new discovery found < 25% of that: REJECT as suspicious
   */
  public static validateCatalogRefresh(
    newApps: DiscoveredApp[],
    currentCatalog: Catalog | null
  ): { isValid: boolean; reason?: string } {
    if (!newApps || newApps.length === 0) {
      return {
        isValid: false,
        reason: 'Discovery returned 0 apps. Aborting catalog overwrite to protect promotions.'
      };
    }

    if (currentCatalog && currentCatalog.apps && currentCatalog.apps.length >= 5) {
      const dropThreshold = Math.max(2, Math.floor(currentCatalog.apps.length * 0.25));
      if (newApps.length < dropThreshold) {
        return {
          isValid: false,
          reason: `Suspicious catalog drop: previous catalog had ${currentCatalog.apps.length} apps, but new discovery only found ${newApps.length}. Overwrite blocked.`
        };
      }
    }

    return { isValid: true };
  }

  /**
   * Merges newly discovered apps with existing catalog:
   * - Preserves `firstDiscoveredAt`
   * - Preserves custom multipliers or `enabledForPromotion` flags
   * - Updates refreshed metadata (installs, descriptions, rating)
   */
  public static mergeWithExistingCatalog(
    newApps: DiscoveredApp[],
    existingCatalog: Catalog | null
  ): DiscoveredApp[] {
    if (!existingCatalog || !existingCatalog.apps || existingCatalog.apps.length === 0) {
      return newApps;
    }

    const existingMap = new Map<string, DiscoveredApp>();
    for (const app of existingCatalog.apps) {
      existingMap.set(app.packageName, app);
    }

    return newApps.map((newApp) => {
      const old = existingMap.get(newApp.packageName);
      if (!old) {
        return newApp; // Brand new app
      }

      // Preserve persistent state while updating dynamic metadata
      return {
        ...newApp,
        firstDiscoveredAt: old.firstDiscoveredAt || newApp.firstDiscoveredAt,
        enabledForPromotion: old.enabledForPromotion !== undefined ? old.enabledForPromotion : newApp.enabledForPromotion,
        promotionMultiplier: old.promotionMultiplier || 1.0,
        recentImpressions: old.recentImpressions || 0,
        // If new metadata is missing a field that old had, fallback to old
        shortDescription: newApp.shortDescription || old.shortDescription,
        fullDescription: newApp.fullDescription || old.fullDescription,
        iconUrl: newApp.iconUrl || old.iconUrl,
        rating: newApp.rating !== null ? newApp.rating : old.rating,
        reviewCount: newApp.reviewCount !== null ? newApp.reviewCount : old.reviewCount,
        installText: newApp.installText || old.installText,
        estimatedMinimumInstalls: newApp.estimatedMinimumInstalls || old.estimatedMinimumInstalls
      };
    });
  }
}
