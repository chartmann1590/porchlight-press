import { Env, Catalog, RecommendationQuery, RecommendationResponse, SystemConfig, AnalyticsEvent } from './types';
import { PlayStoreCatalogProvider } from './discovery/PlayStoreCatalogProvider';
import { RecommendationEngine, TargetPerformance } from './ranking/RecommendationEngine';
import { AnalyticsService } from './analytics/AnalyticsService';
import { AdminService } from './admin/AdminService';

// Module-level in-memory cache for extreme edge performance
let cachedCatalogMemory: { catalog: Catalog; timestamp: number } | null = null;
const MEMORY_CACHE_TTL_MS = 60 * 1000; // 60 seconds in worker memory

function jsonResponse(data: any, status = 200, extraHeaders: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(data, null, 2), {
    status,
    headers: {
      'Content-Type': 'application/json; charset=utf-8',
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
      'Access-Control-Allow-Headers': 'Content-Type, Authorization, X-Admin-Key',
      ...extraHeaders
    }
  });
}

/**
 * Retrieves the normalized catalog using multi-level fallback:
 * 1. Edge isolate memory (TTL 60s)
 * 2. Cloudflare KV current catalog
 * 3. Cloudflare KV last-known-good catalog
 * 4. On-demand dynamic Play Store discovery (validated before persisting)
 */
async function getCatalog(env: Env): Promise<Catalog> {
  const now = Date.now();
  if (cachedCatalogMemory && now - cachedCatalogMemory.timestamp < MEMORY_CACHE_TTL_MS) {
    return cachedCatalogMemory.catalog;
  }

  // Check KV current
  try {
    const current = (await env.CROSS_PROMO_KV.get('catalog:current', 'json')) as Catalog | null;
    if (current && current.apps && current.apps.length > 0) {
      cachedCatalogMemory = { catalog: current, timestamp: now };
      return current;
    }
  } catch (err) {
    console.error('Error reading catalog:current from KV:', err);
  }

  // Check KV last-known-good
  try {
    const lastGood = (await env.CROSS_PROMO_KV.get('catalog:last_known_good', 'json')) as Catalog | null;
    if (lastGood && lastGood.apps && lastGood.apps.length > 0) {
      cachedCatalogMemory = { catalog: lastGood, timestamp: now };
      return lastGood;
    }
  } catch (err) {
    console.error('Error reading catalog:last_known_good from KV:', err);
  }

  // Fallback: On-demand dynamic discovery, subject to the same validation
  // gate as scheduled refreshes so a bad scrape can never poison KV directly.
  console.log('Triggering on-demand catalog discovery from Play Store...');
  const provider = new PlayStoreCatalogProvider(
    env.DEVELOPER_ID || 'Hartmann Studios',
    env.PLAY_STORE_DEV_URL || 'https://play.google.com/store/apps/developer?id=Hartmann+Studios'
  );

  const discovery = await provider.discoverApps();
  const validation = PlayStoreCatalogProvider.validateCatalogRefresh(discovery.apps, null);
  if (!validation.isValid) {
    throw new Error(`On-demand discovery rejected by validation gate: ${validation.reason}`);
  }

  const attemptTime = new Date().toISOString();
  const catalog: Catalog = {
    version: 1,
    generatedAt: attemptTime,
    lastRefreshAttempt: attemptTime,
    lastSuccessfulRefresh: attemptTime,
    discoverySource: `dynamic_fallback:${discovery.strategy}`,
    apps: discovery.apps
  };

  // Cache in KV and memory
  try {
    await env.CROSS_PROMO_KV.put('catalog:current', JSON.stringify(catalog));
    await env.CROSS_PROMO_KV.put('catalog:last_known_good', JSON.stringify(catalog));
  } catch (err) {
    console.error('Error saving dynamic catalog to KV:', err);
  }

  cachedCatalogMemory = { catalog, timestamp: now };
  return catalog;
}

/**
 * Performs scheduled or administrative catalog refresh.
 */
async function refreshCatalog(env: Env): Promise<{ success: boolean; count: number; error?: string }> {
  const provider = new PlayStoreCatalogProvider(
    env.DEVELOPER_ID || 'Hartmann Studios',
    env.PLAY_STORE_DEV_URL || 'https://play.google.com/store/apps/developer?id=Hartmann+Studios'
  );

  const attemptTime = new Date().toISOString();
  let existingCatalog: Catalog | null = null;

  try {
    existingCatalog = (await env.CROSS_PROMO_KV.get('catalog:current', 'json')) as Catalog | null;
  } catch {}

  try {
    const discovery = await provider.discoverApps();
    const validation = PlayStoreCatalogProvider.validateCatalogRefresh(discovery.apps, existingCatalog);

    if (!validation.isValid) {
      console.warn('Catalog refresh validation failed:', validation.reason);
      await env.CROSS_PROMO_KV.put(
        'catalog:meta',
        JSON.stringify({
          lastAttempt: attemptTime,
          lastError: validation.reason,
          rejected: discovery.rejectedCount
        })
      );
      return { success: false, count: 0, error: validation.reason };
    }

    // Merge with existing catalog to preserve discovery dates and multipliers
    const mergedApps = PlayStoreCatalogProvider.mergeWithExistingCatalog(discovery.apps, existingCatalog);

    const newCatalog: Catalog = {
      version: 1,
      generatedAt: attemptTime,
      lastRefreshAttempt: attemptTime,
      lastSuccessfulRefresh: attemptTime,
      discoverySource: `cron:${discovery.strategy}`,
      apps: mergedApps
    };

    await Promise.all([
      env.CROSS_PROMO_KV.put('catalog:current', JSON.stringify(newCatalog)),
      env.CROSS_PROMO_KV.put('catalog:last_known_good', JSON.stringify(newCatalog)),
      env.CROSS_PROMO_KV.put(
        'catalog:meta',
        JSON.stringify({
          lastAttempt: attemptTime,
          lastSuccess: attemptTime,
          discovered: mergedApps.length,
          rejected: discovery.rejectedCount,
          source: discovery.strategy
        })
      )
    ]);

    // Invalidate local memory cache
    cachedCatalogMemory = { catalog: newCatalog, timestamp: Date.now() };

    return { success: true, count: mergedApps.length };
  } catch (err: any) {
    console.error('Fatal error during catalog refresh:', err);
    await env.CROSS_PROMO_KV.put(
      'catalog:meta',
      JSON.stringify({
        lastAttempt: attemptTime,
        lastError: err?.message || 'Unknown error'
      })
    );
    return { success: false, count: 0, error: err?.message };
  }
}

/**
 * Best-effort dead-man's-switch ping so a frozen catalog is loud, not silent.
 * Point HEALTHCHECK_PING_URL at any healthcheck/cron-monitoring service.
 */
async function pingHealthcheck(env: Env, ok: boolean, detail?: string): Promise<void> {
  const url = env.HEALTHCHECK_PING_URL;
  if (!url) return;
  try {
    await fetch(ok ? url : `${url}/fail`, {
      method: 'POST',
      body: detail ? detail.slice(0, 1000) : 'ok'
    });
  } catch (err) {
    console.warn('Healthcheck ping failed:', err);
  }
}

export default {
  /**
   * HTTP request router
   */
  async fetch(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    const url = new URL(request.url);
    const pathname = url.pathname;
    const method = request.method;

    // Handle CORS preflight
    if (method === 'OPTIONS') {
      return jsonResponse({ ok: true });
    }

    // --- 1. HEALTH ENDPOINT ---
    if (pathname === '/api/v1/health' && method === 'GET') {
      try {
        const catalog = await getCatalog(env);
        const ageHours = (Date.now() - new Date(catalog.generatedAt).getTime()) / (1000 * 60 * 60);
        return jsonResponse({
          status: 'ok',
          catalogApps: catalog.apps.length,
          lastCatalogRefresh: catalog.generatedAt,
          lastSuccessfulRefresh: catalog.lastSuccessfulRefresh,
          cacheStatus: ageHours < 12 ? 'fresh' : 'stale'
        });
      } catch (err: any) {
        return jsonResponse(
          {
            status: 'degraded',
            catalogApps: 0,
            error: err.message,
            cacheStatus: 'empty'
          },
          200
        );
      }
    }

    // --- 2. CATALOG ENDPOINT ---
    if (pathname === '/api/v1/catalog' && method === 'GET') {
      try {
        const catalog = await getCatalog(env);
        return jsonResponse(catalog, 200, {
          'Cache-Control': 'public, max-age=3600, stale-while-revalidate=86400'
        });
      } catch (err: any) {
        return jsonResponse(
          {
            version: 1,
            generatedAt: new Date().toISOString(),
            apps: []
          },
          200
        );
      }
    }

    // --- 3. RECOMMENDATIONS ENDPOINT ---
    if (pathname === '/api/v1/recommendations' && method === 'GET') {
      const nowIso = new Date().toISOString();
      const requestId = crypto.randomUUID();

      try {
        const catalog = await getCatalog(env);
        const config = ((await env.CROSS_PROMO_KV.get('config:system', 'json')) as SystemConfig | null) || undefined;

        // Parse & validate query parameters
        const sourcePackage = (url.searchParams.get('sourcePackage') || '').slice(0, 120);
        const placement = (url.searchParams.get('placement') || 'default').slice(0, 50);
        const sessionId = (url.searchParams.get('sessionId') || '').slice(0, 64);
        const locale = (url.searchParams.get('locale') || '').slice(0, 15);
        const limitParam = parseInt(url.searchParams.get('limit') || '0', 10);
        const excludeParam = url.searchParams.get('exclude');

        let excludeList: string[] = [];
        if (excludeParam) {
          excludeList = excludeParam
            .split(',')
            .map((s) => s.trim().slice(0, 120))
            .filter(Boolean);
        }

        const query: RecommendationQuery = {
          sourcePackage,
          placement,
          limit: limitParam > 0 ? limitParam : undefined,
          sessionId: sessionId || undefined,
          exclude: excludeList,
          locale: locale || undefined
        };

        // Feedback loop: recent per-target performance from the analytics
        // store sharpens the Thompson-sampling posterior. Failure here must
        // never break recommendations; ranking degrades to prior-only.
        let targetPerformance: Record<string, TargetPerformance> = {};
        try {
          const analytics = new AnalyticsService(env.CROSS_PROMO_KV);
          targetPerformance = await analytics.getRecentTargetPerformance();
        } catch (err) {
          console.warn('Could not load target performance for ranking:', err);
        }

        const engine = new RecommendationEngine(config);
        const recommendations = engine.generateRecommendations(catalog.apps, query, targetPerformance);

        const ttlSeconds = config?.recommendationTtlSeconds || 14400; // 4 hours
        const expiresAt = new Date(Date.now() + ttlSeconds * 1000).toISOString();

        const responseData: RecommendationResponse = {
          version: 1,
          requestId,
          generatedAt: nowIso,
          expiresAt,
          apps: recommendations
        };

        return jsonResponse(responseData, 200, {
          'Cache-Control': 'private, no-cache'
        });
      } catch (err: any) {
        console.error('Error generating recommendations:', err);
        // Client failure behavior: return valid empty array on error, never fail client app
        return jsonResponse(
          {
            version: 1,
            requestId,
            generatedAt: nowIso,
            expiresAt: nowIso,
            apps: []
          },
          200
        );
      }
    }

    // --- 4. ANALYTICS EVENTS ENDPOINT ---
    if (pathname === '/api/v1/events' && method === 'POST') {
      try {
        const clientIp = request.headers.get('CF-Connecting-IP') || 'unknown';
        const bodyText = await request.text();

        // Security: enforce max body size (16KB)
        if (bodyText.length > 16384) {
          return jsonResponse({ success: false, error: 'Payload too large' }, 413);
        }

        const eventData = JSON.parse(bodyText) as AnalyticsEvent;
        const analytics = new AnalyticsService(env.CROSS_PROMO_KV);
        const result = await analytics.recordEvent(eventData, clientIp);

        return jsonResponse(result, result.success ? 200 : 400);
      } catch (err: any) {
        return jsonResponse({ success: false, error: err.message }, 400);
      }
    }

    // --- 5. ADMIN / DIAGNOSTICS ENDPOINTS ---
    if (pathname.startsWith('/api/v1/admin/')) {
      const adminService = new AdminService(env.CROSS_PROMO_KV, env.ADMIN_SECRET);

      if (!adminService.isAuthorized(request)) {
        return jsonResponse({ error: 'Unauthorized: Invalid admin credentials' }, 401);
      }

      if (pathname === '/api/v1/admin/diagnostics' && method === 'GET') {
        const diagnostics = await adminService.getDiagnostics();
        return jsonResponse(diagnostics);
      }

      if (pathname === '/api/v1/admin/refresh' && method === 'POST') {
        const result = await refreshCatalog(env);
        return jsonResponse(result);
      }

      if (pathname === '/api/v1/admin/config' && method === 'POST') {
        const body = await request.json();
        const updated = await adminService.updateConfig(body as Partial<SystemConfig>);
        return jsonResponse({ success: true, config: updated });
      }
    }

    return jsonResponse({ error: 'Endpoint not found', path: pathname }, 404);
  },

  /**
   * Cloudflare Cron Trigger scheduled handler
   */
  async scheduled(event: ScheduledEvent, env: Env, ctx: ExecutionContext): Promise<void> {
    console.log(`Cron trigger triggered at ${new Date().toISOString()}`);
    ctx.waitUntil(
      (async () => {
        const result = await refreshCatalog(env);
        await pingHealthcheck(env, result.success, result.error);
      })()
    );
  }
};
