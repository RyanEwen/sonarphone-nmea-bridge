/** Prevent ambiguous tags, overflowing arithmetic and incompatible package versions. */
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'

const script = fileURLToPath(new URL('./release-version.sh', import.meta.url))

test('Release lanes derive the same monotonic versionCode', () => {
  for (const [tag, code] of [['v0.2.4', 204], ['v0.2.5', 205], ['v0.99.99', 9999], ['v1.0.0', 10000]]) {
    const result = spawnSync('bash', [script, tag], { encoding: 'utf8' })
    assert.equal(result.status, 0, result.stderr)
    assert.equal(result.stdout, `tag=${tag}\nname=${tag.slice(1)}\ncode=${code}\n`)
  }
})

test('Release tags cannot alias existing versions or overflow into a valid code', () => {
  for (const tag of ['main', 'v0.0.0', 'v0.2.100', 'v0.100.0', 'v0.2.4-pre', 'v0.02.4', 'v00.2.4', 'v210000.0.1', 'v18446744073709551616.2.4']) {
    const result = spawnSync('bash', [script, tag], { encoding: 'utf8' })
    assert.notEqual(result.status, 0, tag)
    assert.equal(result.stdout, '', tag)
  }
})
