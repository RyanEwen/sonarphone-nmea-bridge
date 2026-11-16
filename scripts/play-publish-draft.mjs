/**
 * Send the production draft a tag created to Google Play review (REL-7, second step).
 * Nothing is uploaded or rebuilt: the draft must already hold exactly the versionCode
 * computed from the tag, and any failed check discards the temporary edit.
 */
import { resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { getPlayAccessToken, playRequest, readPlayServiceAccount } from './play-api.mjs'
import { PLAY_PACKAGE } from './play-apk.mjs'

/**
 * Commit the production draft for `code` as a completed release.
 * `code` is the versionCode derived from the release tag; the draft is rejected
 * unless it contains exactly that code and production holds nothing newer.
 * Returns the published versionCode.
 */
export async function publishPlayDraft(token, code, request = playRequest) {
  if (!/^[1-9]\d*$/.test(String(code))) throw new Error('Use the numeric Play versionCode derived from the release tag.')
  const base = `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${PLAY_PACKAGE}`
  const call = async (path, method = 'GET', body) => {
    const response = await request(`${base}${path}`, {
      method,
      headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: AbortSignal.timeout(60_000)
    }, 'Google Play draft publication')
    if (!response.ok) throw new Error(`Could not publish the Play draft (HTTP ${response.status}).`)
    return method === 'DELETE' ? null : response.json()
  }

  const edit = await call('/edits', 'POST')
  let committed = false
  try {
    const tracks = await call(`/edits/${edit.id}/tracks`)
    const production = (tracks.tracks ?? []).find((track) => track.track === 'production')
    const releases = production?.releases ?? []

    // The draft must be this tag's build and nothing else.
    const draft = releases.find((release) => release.status === 'draft')
    if (!draft) throw new Error('Production has no draft release. Run the tag workflow first.')
    const draftCodes = (draft.versionCodes ?? []).map(String)
    if (draftCodes.length !== 1 || draftCodes[0] !== String(code)) {
      throw new Error(`The production draft holds versionCode ${draftCodes.join(', ') || 'none'}, not ${code} from the tag.`)
    }

    // Never publish over a release that is already live with this or a newer code.
    const liveCodes = releases
      .filter((release) => release.status !== 'draft')
      .flatMap((release) => release.versionCodes ?? [])
      .map(Number)
    if (liveCodes.some((existing) => existing >= Number(code))) {
      throw new Error('Production already contains this versionCode or a newer release.')
    }

    await call(`/edits/${edit.id}/tracks/production`, 'PUT', {
      track: 'production',
      releases: [{ name: draft.name, versionCodes: draftCodes, status: 'completed', releaseNotes: draft.releaseNotes }]
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
  const result = await publishPlayDraft(token, process.env.PLAY_VERSION_CODE)
  console.log(`Sent production versionCode ${result.versionCode} to Google Play review.`)
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch((error) => {
    console.error(error.message)
    process.exitCode = 1
  })
}
