import { describe, it, expect } from 'vitest';
import worker from '../src/index';
import { Env } from '../src/types';
import * as fs from 'fs';
import * as path from 'path';

class MockKV implements Partial<KVNamespace> {
  private map = new Map<string, string>();
  async get(key: string, type?: string): Promise<any> {
    const val = this.map.get(key);
    if (!val) return null;
    if (type === 'json') return JSON.parse(val);
    return val;
  }
  async put(key: string, val: string): Promise<void> {
    this.map.set(key, val);
  }
}

describe('Worker Endpoints', () => {
  it('handles /api/v1/recommendations without error', async () => {
    const kv = new MockKV() as unknown as KVNamespace;
    const fixturePath = path.join(__dirname, 'fixtures', 'play_dev_page.html');
    const html = fs.readFileSync(fixturePath, 'utf8');

    // Pre-populate catalog
    const provider = new (await import('../src/discovery/PlayStoreCatalogProvider')).PlayStoreCatalogProvider();
    const discovery = await provider.discoverApps(html);
    await kv.put('catalog:current', JSON.stringify({
      version: 1,
      generatedAt: new Date().toISOString(),
      lastRefreshAttempt: new Date().toISOString(),
      lastSuccessfulRefresh: new Date().toISOString(),
      discoverySource: 'test',
      apps: discovery.apps
    }));

    const env: Env = {
      CROSS_PROMO_KV: kv
    };

    const req = new Request('https://example.com/api/v1/recommendations?sourcePackage=com.charles.nutrisnap&placement=settings&limit=3');
    const ctx = {} as ExecutionContext;
    const res = await worker.fetch(req, env, ctx);

    expect(res.status).toBe(200);
    const body = await res.json() as any;
    expect(body.version).toBe(1);
    expect(body.apps.length).toBe(3);
    expect(body.apps.some((a: any) => a.packageName === 'com.charles.nutrisnap')).toBe(false);
  });
});
