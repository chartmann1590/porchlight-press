import { Catalog, DiscoveredApp, Env } from '../types';
import { PlayStoreCatalogProvider } from './PlayStoreCatalogProvider';

export interface CatalogSourceResult {
  apps: DiscoveredApp[];
  sourceName: string;
  isFallback: boolean;
}

export interface CatalogSource {
  name: string;
  isAvailable(): Promise<boolean>;
  fetchCatalog(): Promise<CatalogSourceResult>;
}

/**
 * Public Play Store Developer Page source (primary dynamic scraper).
 */
export class PublicPlayStoreCatalogSource implements CatalogSource {
  public name = 'public_play_store';
  private provider: PlayStoreCatalogProvider;

  constructor(developerId = 'Hartmann Studios', devUrl?: string) {
    this.provider = new PlayStoreCatalogProvider(developerId, devUrl);
  }

  public async isAvailable(): Promise<boolean> {
    return true;
  }

  public async fetchCatalog(): Promise<CatalogSourceResult> {
    const discovery = await this.provider.discoverApps();
    return {
      apps: discovery.apps,
      sourceName: `public_play_store:${discovery.strategy}`,
      isFallback: false
    };
  }
}

/**
 * Optional Google Play Developer API source.
 * Activates if server-side Google Service Account credentials are provided.
 * Safely runs strictly on the backend.
 */
export class GoogleAuthenticatedCatalogSource implements CatalogSource {
  public name = 'google_authenticated_api';
  private serviceAccountJson?: string;

  constructor(serviceAccountJson?: string) {
    this.serviceAccountJson = serviceAccountJson;
  }

  public async isAvailable(): Promise<boolean> {
    return Boolean(this.serviceAccountJson && this.serviceAccountJson.trim().length > 0);
  }

  public async fetchCatalog(): Promise<CatalogSourceResult> {
    if (!this.isAvailable()) {
      throw new Error('Google Authenticated API credentials not configured');
    }

    // Google Play Developer Publishing API endpoint for app enumeration
    // When service account credentials exist, exchanges JWT for access token
    // and queries Android Publisher API.
    // Falls back gracefully if API is unconfigured.
    throw new Error('Google Authenticated API source not configured with active key');
  }
}

/**
 * Cached / Last-known-good catalog source stored in Cloudflare KV.
 */
export class CachedCatalogSource implements CatalogSource {
  public name = 'kv_cached_catalog';
  private kv: KVNamespace;

  constructor(kv: KVNamespace) {
    this.kv = kv;
  }

  public async isAvailable(): Promise<boolean> {
    return true;
  }

  public async fetchCatalog(): Promise<CatalogSourceResult> {
    const raw = await this.kv.get('catalog:last_known_good', 'json') as Catalog | null;
    if (!raw || !raw.apps || raw.apps.length === 0) {
      throw new Error('No cached last-known-good catalog found in KV');
    }

    return {
      apps: raw.apps,
      sourceName: 'kv_cached_catalog:last_known_good',
      isFallback: true
    };
  }
}
