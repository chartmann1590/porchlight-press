import { describe, it, expect } from 'vitest';
import * as fs from 'fs';
import * as path from 'path';
import worker from '../src/index';
import { Env } from '../src/types';

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

/**
 * Minimal JSON Schema (draft-07 subset) validator — enough for the
 * recommendation contract without adding a runtime dependency.
 */
function validateAgainstSchema(value: any, schema: any, root: any = schema, errors: string[] = [], prefix = '$'): string[] {
  if (schema.$ref) {
    const refPath = schema.$ref.replace(/^#\//, '').split('/');
    let resolved: any = root;
    for (const part of refPath) resolved = resolved[part];
    return validateAgainstSchema(value, resolved, root, errors, prefix);
  }

  const type = schema.type;
  if (type) {
    const types = Array.isArray(type) ? type : [type];
    const matchesType = (t: string): boolean => {
      if (t === 'integer') return typeof value === 'number' && Number.isInteger(value);
      if (t === 'null') return value === null;
      if (t === 'array') return Array.isArray(value);
      return typeof value === t;
    };
    if (!types.some(matchesType)) {
      const actual = Array.isArray(value)
        ? 'array'
        : value === null
          ? 'null'
          : typeof value;
      errors.push(`${prefix}: expected ${types.join('|')}, got ${actual}`);
      return errors;
    }
  }

  if (schema.enum && !schema.enum.includes(value)) {
    errors.push(`${prefix}: value not in enum ${JSON.stringify(schema.enum)}`);
  }

  if (typeof value === 'number') {
    if (schema.minimum !== undefined && value < schema.minimum) {
      errors.push(`${prefix}: ${value} < minimum ${schema.minimum}`);
    }
    if (schema.maximum !== undefined && value > schema.maximum) {
      errors.push(`${prefix}: ${value} > maximum ${schema.maximum}`);
    }
  }

  if (typeof value === 'string') {
    if (schema.minLength !== undefined && value.length < schema.minLength) {
      errors.push(`${prefix}: shorter than minLength ${schema.minLength}`);
    }
    if (schema.pattern && !new RegExp(schema.pattern).test(value)) {
      errors.push(`${prefix}: does not match pattern ${schema.pattern}`);
    }
  }

  if (Array.isArray(value) && schema.items) {
    value.forEach((item, i) => validateAgainstSchema(item, schema.items, root, errors, `${prefix}[${i}]`));
  }

  if (value && typeof value === 'object' && !Array.isArray(value)) {
    for (const req of schema.required ?? []) {
      if (!(req in value)) errors.push(`${prefix}: missing required property "${req}"`);
    }
    for (const [key, subschema] of Object.entries<any>(schema.properties ?? {})) {
      if (key in value) validateAgainstSchema(value[key], subschema, root, errors, `${prefix}.${key}`);
    }
  }

  return errors;
}

describe('Recommendation response contract', () => {
  it('live worker responses satisfy schemas/crosspromo-recommendation-response.schema.json', async () => {
    const schemaPath = path.join(__dirname, '..', '..', 'schemas', 'crosspromo-recommendation-response.schema.json');
    const schema = JSON.parse(fs.readFileSync(schemaPath, 'utf8'));

    const kv = new MockKV() as unknown as KVNamespace;
    const fixturePath = path.join(__dirname, 'fixtures', 'play_dev_page.html');
    const html = fs.readFileSync(fixturePath, 'utf8');
    const { PlayStoreCatalogProvider } = await import('../src/discovery/PlayStoreCatalogProvider');
    const discovery = await new PlayStoreCatalogProvider().discoverApps(html);

    await kv.put('catalog:current', JSON.stringify({
      version: 1,
      generatedAt: new Date().toISOString(),
      lastRefreshAttempt: new Date().toISOString(),
      lastSuccessfulRefresh: new Date().toISOString(),
      discoverySource: 'test',
      apps: discovery.apps
    }));

    const env = { CROSS_PROMO_KV: kv } as unknown as Env;
    const req = new Request('https://example.com/api/v1/recommendations?sourcePackage=com.charles.nutrisnap&placement=settings&limit=3');
    const res = await worker.fetch(req, env, {} as ExecutionContext);
    expect(res.status).toBe(200);

    const body = await res.json();
    const errors = validateAgainstSchema(body, schema);
    expect(errors).toEqual([]);
  });
});
