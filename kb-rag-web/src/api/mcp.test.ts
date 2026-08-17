import { describe, expect, it } from 'vitest';
import { buildMcpHeaders, buildMcpRequest } from './mcp';

describe('MCP dual protocol request builder', () => {
  it('adds per-request metadata and mirrored headers for the modern protocol', () => {
    const request = buildMcpRequest('modern', 1, 'tools/call', {
      name: '知识检索',
      arguments: { query: 'hello' },
    });
    const headers = buildMcpHeaders('modern', 'tools/call', { name: '知识检索' });

    expect(request.params).toMatchObject({
      name: '知识检索',
      _meta: {
        'io.modelcontextprotocol/protocolVersion': '2026-07-28',
        'io.modelcontextprotocol/clientCapabilities': {},
      },
    });
    expect(headers['MCP-Protocol-Version']).toBe('2026-07-28');
    expect(headers['Mcp-Method']).toBe('tools/call');
    expect(headers['Mcp-Name']).toMatch(/^=\?base64\?.+\?=$/);
  });

  it('preserves the legacy initialize request shape without modern metadata', () => {
    const request = buildMcpRequest('legacy', 2, 'initialize', {
      protocolVersion: '2025-03-26',
      capabilities: {},
    });
    const headers = buildMcpHeaders('legacy', 'initialize');

    expect(request.params).toEqual({ protocolVersion: '2025-03-26', capabilities: {} });
    expect(JSON.stringify(request)).not.toContain('_meta');
    expect(headers).not.toHaveProperty('MCP-Protocol-Version');
    expect(headers.Accept).toContain('text/event-stream');
  });
});
