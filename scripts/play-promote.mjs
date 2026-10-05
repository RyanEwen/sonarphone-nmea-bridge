/** Promote an existing verified bundle to production without uploading or rebuilding it. */
import { resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { getPlayAccessToken, playRequest, readPlayServiceAccount } from './play-api.mjs'
import { PLAY_PACKAGE } from './play-apk.mjs'

/** Validate a promotion before committing; discard the temporary edit on every failure. */
export async function promotePlayRelease(token, code, request = playRequest) {
  if (!/^[1-9]\d*$/.test(String(code))) throw new Error('Use an existing numeric Play versionCode.')
  const base = `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${PLAY_PACKAGE}`
  const call = async (path, method = 'GET', body) => {
    const response = await request(`${base}${path}`, {
      method,
      headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: AbortSignal.timeout(60_000)
    }, 'Google Play production promotion')
    if (!response.ok) throw new Error(`Could not promote Play release (HTTP ${response.status}).`)
    return method === 'DELETE' ? null : response.json()
  }

  const edit = await call('/edits', 'POST')
  let committed = false
  try {
    const bundles = await call(`/edits/${edit.id}/bundles`)
    if (!(bundles.bundles ?? []).some((bundle) => String(bundle.versionCode) === String(code))) {
      throw new Error('Requested versionCode has not been uploaded to Play.')
    }

    const tracks = await call(`/edits/${edit.id}/tracks`)
    const production = (tracks.tracks ?? []).find((track) => track.track === 'production')
    const existingCodes = (production?.releases ?? []).flatMap((release) => release.versionCodes ?? []).map(Number)
    if (existingCodes.some((existing) => existing >= Number(code))) {
      throw new Error('Production already contains this versionCode or a newer release.')
    }

    const source = (tracks.tracks ?? [])
      .flatMap((track) => track.releases ?? [])
      .find((release) => release.status === 'completed' && (release.versionCodes ?? []).includes(String(code)))
    if (!source) throw new Error('Requested versionCode is not a completed testing release.')

    // Update production alone; testing tracks remain intact for migration and validation.
    await call(`/edits/${edit.id}/tracks/production`, 'PUT', {
      track: 'production',
      releases: [{ name: source.name, versionCodes: [String(code)], status: 'completed', releaseNotes: source.releaseNotes }]
    })
    await call(`/edits/${edit.id}:validate`, 'POST')
    await call(`/edits/${edit.id}:commit`, 'POST')
    committed = true
    return { versionCode: Number(code), track: 'production' }
  } finally {
    if (!committed) await call(`/edits/${edit.id}`, 'DELETE')
  }
}

/** Authenticate only inside CI; never expose credentials in logs. */
async function main() {
  const token = await getPlayAccessToken(readPlayServiceAccount(process.env.PLAY_SERVICE_ACCOUNT_JSON))
  const result = await promotePlayRelease(token, process.env.PROMOTE_VERSION_CODE)
  console.log(`Promoted existing versionCode ${result.versionCode} to Google Play production.`)
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch((error) => {
    console.error(error.message)
    process.exitCode = 1
  })
}
