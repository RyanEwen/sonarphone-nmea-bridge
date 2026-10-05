/** Fail closed when exporting an APK with the wrong package, version or signing identity. */
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { downloadPlayApk, normalizeCertificate, selectUniversalApk, trustedPlayCertificates, verifyApkIdentity } from './play-apk.mjs'

const certificate = 'ab'.repeat(32)
const trusted = new Set([certificate])
const generated = { certificateSha256Hash: certificate, generatedUniversalApk: { downloadId: 'one/two' } }

test('Play exporter reads only the registered Play certificate, accepting colon notation', () => {
  assert.equal(normalizeCertificate(certificate.toUpperCase().match(/../g).join(':')), certificate)
  assert.deepEqual(trustedPlayCertificates(certificate), trusted)
  assert.throws(() => trustedPlayCertificates(''), /not set/)
  assert.throws(() => trustedPlayCertificates('invalid'), /Invalid/)
})

test('Play exporter refuses unknown or ambiguous universal signing identities', () => {
  assert.equal(selectUniversalApk({ generatedApks: [generated] }, trusted), generated)
  assert.equal(selectUniversalApk({ generatedApks: [{ generatedSplitApks: [] }] }, trusted), null)
  assert.throws(() => selectUniversalApk({ generatedApks: [{ ...generated, certificateSha256Hash: 'cd'.repeat(32) }] }, trusted), /unregistered/)
  assert.throws(() => selectUniversalApk({ generatedApks: [generated, generated] }, trusted), /Multiple/)
})

test('APK proof rejects wrong package, version and cryptographic signer', () => {
  const badging = "package: name='ca.dynamicsolutions.sonarbridge' versionCode='50' versionName='0.26.0' platformBuildVersionName='16'"
  const signing = `Signer #1 certificate SHA-256 digest: ${certificate}`
  const expected = { code: 50, version: '0.26.0', certificate }
  verifyApkIdentity(badging, signing, expected)
  assert.throws(() => verifyApkIdentity(badging.replace('ca.dynamicsolutions.sonarbridge', 'ca.wrong.app'), signing, expected), /package or version/)
  assert.throws(() => verifyApkIdentity(badging, signing, { ...expected, code: 51 }), /package or version/)
  assert.throws(() => verifyApkIdentity(badging, signing.replace(certificate, 'cd'.repeat(32)), expected), /signer/)
  assert.throws(() => verifyApkIdentity(badging, `${signing}\nSigner #2 certificate SHA-256 digest: ${certificate}`, expected), /signer/)
})

test('APK export waits for generation, downloads encoded ID and returns verified candidate', async () => {
  const urls = []
  const waits = []
  const responses = [new Response('', { status: 404 }), Response.json({ generatedApks: [] }), Response.json({ generatedApks: [generated] }), new Response('APK bytes')]
  const result = await downloadPlayApk({
    token: 'token', code: 50, trusted,
    fetchRequest: async (url, init) => {
      urls.push(url)
      assert.equal(init.headers.Authorization, 'Bearer token')
      return responses.shift()
    },
    sleep: async (ms) => { waits.push(ms) }
  })
  assert.deepEqual(waits, [30_000, 30_000])
  assert.match(urls[3], /\/50\/downloads\/one%2Ftwo:download\?alt=media$/)
  assert.equal(result.bytes.toString(), 'APK bytes')
  assert.equal(result.certificate, certificate)
})

test('APK export does not retry permission failures or fall back when universal APK is absent', async () => {
  let requests = 0
  await assert.rejects(downloadPlayApk({ token: 'token', code: 50, trusted, fetchRequest: async () => {
    requests++
    return new Response('', { status: 403 })
  } }), /HTTP 403/)
  assert.equal(requests, 1)
  await assert.rejects(downloadPlayApk({ token: 'token', code: 50, trusted, attempts: 2,
    fetchRequest: async () => Response.json({ generatedApks: [] }), sleep: async () => {}
  }), /no direct APK will be published/)
})
