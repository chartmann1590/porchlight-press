import { describe, it, expect } from 'vitest';
import { AdminService } from '../src/admin/AdminService';

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

function authorizedRequest(auth: string | null, extraHeaders: Record<string, string> = {}): Request {
  const headers = new Headers(extraHeaders);
  if (auth) headers.set('Authorization', auth);
  return new Request('https://example.com/api/v1/admin/diagnostics', { headers });
}

describe('AdminService authorization', () => {
  it('authorizes a correct bearer token', () => {
    const svc = new AdminService(new MockKV() as unknown as KVNamespace, 'real-secret-value');
    expect(svc.isAuthorized(authorizedRequest('Bearer real-secret-value'))).toBe(true);
  });

  it('authorizes a correct X-Admin-Key header', () => {
    const svc = new AdminService(new MockKV() as unknown as KVNamespace, 'real-secret-value');
    expect(
      svc.isAuthorized(authorizedRequest(null, { 'X-Admin-Key': 'real-secret-value' }))
    ).toBe(true);
  });

  it('rejects wrong or missing credentials', () => {
    const svc = new AdminService(new MockKV() as unknown as KVNamespace, 'real-secret-value');
    expect(svc.isAuthorized(authorizedRequest('Bearer wrong'))).toBe(false);
    expect(svc.isAuthorized(authorizedRequest(null))).toBe(false);
  });

  it('refuses when no secret is configured at all', () => {
    const svc = new AdminService(new MockKV() as unknown as KVNamespace, undefined);
    expect(svc.isAuthorized(authorizedRequest('Bearer anything'))).toBe(false);
  });

  it('refuses obvious placeholder secrets even if configured', () => {
    for (const placeholder of ['hartmann-crosspromo-admin-sec-2026', 'changeme', 'admin']) {
      const svc = new AdminService(new MockKV() as unknown as KVNamespace, placeholder);
      expect(svc.isAuthorized(authorizedRequest(`Bearer ${placeholder}`))).toBe(false);
    }
  });

  it('does not accept credentials from the query string', () => {
    const svc = new AdminService(new MockKV() as unknown as KVNamespace, 'real-secret-value');
    const req = new Request('https://example.com/api/v1/admin/diagnostics?adminKey=real-secret-value');
    expect(svc.isAuthorized(req)).toBe(false);
  });
});
