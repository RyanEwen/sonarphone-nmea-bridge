/** Publishing must commit only the tag's existing production draft, and never a rejected edit. */
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { publishPlayDraft } from './play-publish-draft.mjs'

const tracks = { tracks: [
  { track: 'production', releases: [
    { versionCodes: ['206'], status: 'completed' },
    { name: '0.2.7', versionCodes: ['207'], status: 'draft', releaseNotes: [{ language: 'en-US', text: '- x' }] }
  ] },
  { track: 'internal', releases: [{ versionCodes: ['205'], status: 'completed' }] }
] }

/** Queue API responses while recording every request to prove failed checks cannot publish. */
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

test('The tag\'s production draft is completed, validated and committed without an upload', async () => {
  const api = transport([{ id: 'temporary' }, tracks, {}, {}, {}])
  assert.deepEqual(await publishPlayDraft('token', '207', api.request), { versionCode: 207, track: 'production' })
  const changes = api.calls.filter((call) => call.method === 'PUT')
  assert.equal(changes.length, 1)
  assert.ok(changes[0].url.endsWith('/tracks/production'))
  assert.deepEqual(changes[0].body.releases, [{ name: '0.2.7', versionCodes: ['207'], status: 'completed', releaseNotes: [{ language: 'en-US', text: '- x' }] }])
  assert.ok(api.calls[3].url.endsWith(':validate'))
  assert.ok(api.calls[4].url.endsWith(':commit'))
  assert.equal(api.calls.some((call) => call.url.includes('/bundles')), false)
})

test('A draft for another version, no draft, or an older code is refused before any change', async () => {
  const noDraft = { tracks: [{ track: 'production', releases: [{ versionCodes: ['206'], status: 'completed' }] }] }
  const olderThanLive = { tracks: [{ track: 'production', releases: [
    { versionCodes: ['208'], status: 'completed' },
    { versionCodes: ['207'], status: 'draft' }
  ] }] }
  for (const [code, state, expected] of [['208', tracks, /holds versionCode 207, not 208/], ['207', noDraft, /no draft/], ['207', olderThanLive, /already contains/]]) {
    const api = transport([{ id: 'temporary' }, state, new Response(null, { status: 204 })])
    await assert.rejects(publishPlayDraft('token', code, api.request), expected)
    assert.equal(api.calls.some((call) => call.method === 'PUT'), false)
    assert.equal(api.calls.at(-1).method, 'DELETE')
  }
})

test('A validation failure discards the edit and cannot commit', async () => {
  const api = transport([{ id: 'temporary' }, tracks, {}, new Response(null, { status: 403 }), new Response(null, { status: 204 })])
  await assert.rejects(publishPlayDraft('token', 207, api.request), /HTTP 403/)
  assert.equal(api.calls.some((call) => call.url.endsWith(':commit')), false)
  assert.equal(api.calls.at(-1).method, 'DELETE')
})

test('Only a numeric versionCode is accepted', async () => {
  await assert.rejects(publishPlayDraft('token', 'v0.2.7', async () => assert.fail('no request expected')), /numeric/)
})
