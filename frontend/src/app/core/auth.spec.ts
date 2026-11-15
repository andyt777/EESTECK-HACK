import { describe, expect, it } from 'vitest';
import { oidcConfig } from './auth';

describe('oidcConfig', () => {
  it('uses the realm behind the gateway on the same origin, code + PKCE with refresh tokens', () => {
    const config = (oidcConfig('https://localhost') as { config: Record<string, unknown> }).config;
    expect(config['authority']).toBe('https://localhost/auth/realms/filegrid');
    expect(config['clientId']).toBe('filegrid-web');
    expect(config['responseType']).toBe('code');
    expect(config['useRefreshToken']).toBe(true);
    expect(config['secureRoutes']).toEqual(['/api/']);
    expect(config['redirectUrl']).toBe('https://localhost');
  });
});
