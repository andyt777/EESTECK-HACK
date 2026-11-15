import { describe, expect, it } from 'vitest';
import { parseSse } from './events.service';

describe('parseSse', () => {
  it('splits complete events and keeps the unfinished rest', () => {
    const { events, rest } = parseSse('event: file-event\ndata: {"a":1}\n\nevent: file-event\ndata: {"a"');
    expect(events).toEqual([{ event: 'file-event', data: '{"a":1}' }]);
    expect(rest).toBe('event: file-event\ndata: {"a"');
  });

  it('joins multi-line data, accepts CRLF and defaults the event name', () => {
    const { events } = parseSse('data: line1\r\ndata: line2\r\n\r\n');
    expect(events).toEqual([{ event: 'message', data: 'line1\nline2' }]);
  });

  it('ignores comments and heartbeats without data', () => {
    const { events, rest } = parseSse(':keep-alive\n\nevent: incident-event\nid: 7\ndata:{"x":true}\n\n');
    expect(events).toEqual([{ event: 'incident-event', data: '{"x":true}' }]);
    expect(rest).toBe('');
  });
});
