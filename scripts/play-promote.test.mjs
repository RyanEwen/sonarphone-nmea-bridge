/** Promotion must reuse an uploaded testing bundle and never commit a rejected edit. */
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { promotePlayRelease } from './play-promote.mjs'

const bundles = { bundles: [{ versionCode: 205 }] }
const tracks = { tracks: [
  { track: 'production', releases: [{ versionCodes: ['203'], status: 'completed' }] },
  { track: 'internal', releases: [{ name: '0.2.5', versionCodes: ['205'], status: 'completed' }] }
] }

/** Queue API responses while recording every mutation to prove failed checks cannot publish. */
function transport(values) {
  const calls = []
  return {
    calls,
    request: async (url, init) => {
      calls.push({ url, method: init.method, body: init.body ? JSON.parse(init.body) : undefined })
      const value = values.shift()
      assert.notEqual(value, undefined, 'unexpected API request')
      return value instanceof Response ? value : Response.json(value)
    }
  }
}

test('Production promotion validates and commits the existing testing bundle without an upload', async () => {
  const api = transport([{ id: 'temporary' }, bundles, tracks, {}, {}, {}])
  assert.deepEqual(await promotePlayRelease('token', '205', api.request), { versionCode: 205, track: 'production' })
  const changes = api.calls.filter((call) => call.method === 'PUT')
  assert.equal(changes.length, 1)
  assert.ok(changes[0].url.endsWith('/tracks/production'))
  assert.deepEqual(changes[0].body.releases[0].versionCodes, ['205'])
  assert.ok(api.calls[4].url.endsWith(':validate'))
  assert.ok(api.calls[5].url.endsWith(':commit'))
  assert.equal(api.calls.some((call) => call.url.includes('/bundles') && call.method !== 'GET'), false)
})

test('Production promotion rejects missing or older versions before changing the track', async () => {
  for (const [code, expected] of [['206', /not been uploaded/], ['203', /already contains/]]) {
    const existing = code === '203' ? { bundles: [{ versionCode: 203 }] } : bundles
    const values = [{ id: 'temporary' }, existing]
    if (code === '203') values.push(tracks)
    values.push(new Response(null, { status: 204 }))
    const api = transport(values)
    await assert.rejects(promotePlayRelease('token', code, api.request), expected)
    assert.equal(api.calls.some((call) => call.method === 'PUT'), false)
    assert.equal(api.calls.at(-1).method, 'DELETE')
  }
})

test('A validation failure discards the edit and cannot commit a production release', async () => {
  const api = transport([{ id: 'temporary' }, bundles, tracks, {}, new Response(null, { status: 403 }), new Response(null, { status: 204 })])
  await assert.rejects(promotePlayRelease('token', 205, api.request), /HTTP 403/)
  assert.equal(api.calls.some((call) => call.url.endsWith(':commit')), false)
  assert.equal(api.calls.at(-1).method, 'DELETE')
})
