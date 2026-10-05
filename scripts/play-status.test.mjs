/** Play preflight inspection must discard edits, including after API failure. */
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { inspectPlayState } from './play-status.mjs'

test('Play state inspection reads tracks/bundles and deletes its uncommitted edit', async () => {
  const calls = []
  const responses = [Response.json({ id: 'temporary' }), Response.json({ tracks: [{ track: 'internal' }] }), Response.json({ bundles: [{ versionCode: 203 }] }), new Response(null, { status: 204 })]
  const state = await inspectPlayState('token', async (url, init) => {
    calls.push([url, init.method])
    return responses.shift()
  })
  assert.deepEqual(state, { tracks: [{ track: 'internal' }], bundles: [{ versionCode: 203 }] })
  assert.deepEqual(calls.map(([url, method]) => [new URL(url).pathname.split('/edits')[1], method]), [
    ['', 'POST'], ['/temporary/tracks', 'GET'], ['/temporary/bundles', 'GET'], ['/temporary', 'DELETE']
  ])
})

test('Play state inspection still discards its edit after a permission failure', async () => {
  const calls = []
  const responses = [Response.json({ id: 'temporary' }), new Response(null, { status: 403 }), new Response(null, { status: 204 })]
  await assert.rejects(inspectPlayState('token', async (url, init) => {
    calls.push(init.method)
    return responses.shift()
  }), /HTTP 403/)
  assert.deepEqual(calls, ['POST', 'GET', 'DELETE'])
})
