/** Inspect existing Play releases without publishing; temporary edits are always discarded. */
import { resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { getPlayAccessToken, playRequest, readPlayServiceAccount } from './play-api.mjs'
import { PLAY_PACKAGE } from './play-apk.mjs'

/** Return active tracks and uploaded bundle codes, leaving the Play app unchanged. */
export async function inspectPlayState(token, request = playRequest) {
  const base = `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${PLAY_PACKAGE}`
  const call = async (path, method = 'GET') => {
    const response = await request(`${base}${path}`, {
      method,
      headers: { Authorization: `Bearer ${token}` },
      signal: AbortSignal.timeout(60_000)
    }, 'Google Play release state')
    if (!response.ok) throw new Error(`Could not inspect Play release state (HTTP ${response.status}).`)
    return method === 'DELETE' ? null : response.json()
  }

  const edit = await call('/edits', 'POST')
  try {
    const tracks = await call(`/edits/${edit.id}/tracks`)
    const bundles = await call(`/edits/${edit.id}/bundles`)
    return { tracks: tracks.tracks ?? [], bundles: bundles.bundles ?? [] }
  } finally {
    // Inspection never validates or commits this edit, so tracks and listing remain unchanged.
    await call(`/edits/${edit.id}`, 'DELETE')
  }
}

/** Print only public release and signer information, never credentials or download tokens. */
async function main() {
  const token = await getPlayAccessToken(readPlayServiceAccount(process.env.PLAY_SERVICE_ACCOUNT_JSON))
  const state = await inspectPlayState(token)
  const codes = state.bundles.map((bundle) => Number(bundle.versionCode))
  const highestCode = Math.max(0, ...codes)
  console.log(JSON.stringify({ tracks: state.tracks, versionCodes: codes }, null, 2))

  if (process.env.ANDROID_VERSION_CODE) {
    const proposed = Number(process.env.ANDROID_VERSION_CODE)
    if (!Number.isSafeInteger(proposed) || proposed <= highestCode) {
      throw new Error(`versionCode ${proposed} must exceed uploaded Play versionCode ${highestCode}. Export an existing version instead of uploading it again.`)
    }
    return
  }

  if (!highestCode) throw new Error('No existing Play bundle to inspect.')
  const response = await playRequest(`https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${PLAY_PACKAGE}/generatedApks/${highestCode}`, {
    headers: { Authorization: `Bearer ${token}` },
    signal: AbortSignal.timeout(60_000)
  }, 'Google Play signing metadata')
  if (!response.ok) throw new Error(`Could not inspect Play signing metadata (HTTP ${response.status}).`)
  const metadata = await response.json()
  console.log(JSON.stringify({ versionCode: highestCode, signers: (metadata.generatedApks ?? []).map((entry) => ({
    certificateSha256Hash: entry.certificateSha256Hash,
    universalApkAvailable: Boolean(entry.generatedUniversalApk?.downloadId)
  })) }, null, 2))
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch((error) => {
    console.error(error.message)
    process.exitCode = 1
  })
}
