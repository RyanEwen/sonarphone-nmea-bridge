/**
 * Committed release notes (REL-6): check them, and turn one version's notes into
 * the Play "What's new" files and the GitHub Release body.
 *
 *   node scripts/release-notes.mjs check
 *       Validate every file in release-notes/ (used by `npm run validate`).
 *   node scripts/release-notes.mjs play vX.Y.Z <versionCode> <metadataDir>
 *       Write <metadataDir>/<locale>/changelogs/<versionCode>.txt for every Play listing locale.
 *   node scripts/release-notes.mjs github vX.Y.Z
 *       Print the GitHub Release body: the en-US notes, the Google Play link and the AI disclosure line.
 *
 * Notes are never generated from commits and never truncated: a missing sibling
 * or over-long text fails the command.
 */
import { readFileSync, readdirSync, mkdirSync, writeFileSync, existsSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { PLAY_PACKAGE } from './play-apk.mjs'

/** The source catalog: release-notes/<version>.md holds this identity's text. */
export const SOURCE_IDENTITY = 'en-US'

/** Every other wording catalog the app ships, one sibling file each. */
export const SIBLING_IDENTITIES = ['es-ES', 'fr-FR', 'de-DE', 'zh-CN', 'pt-BR', 'ja-JP']

/** Play listing locales that reuse another catalog's text (LOC-5 aliases). */
export const LISTING_ALIASES = { 'en-CA': 'en-US', 'fr-CA': 'fr-FR' }

/** Play's "What's new" limit, the shortest store the notes go to (LIST-10). */
export const PLAY_NOTES_LIMIT = 500

/** Appended by CI to every GitHub Release (D-097); never part of the notes file. */
export const DISCLOSURE_LINE = '*AI disclosure: this release and the related code were written with the assistance of AI.*'

const FILE_NAME = /^(\d+\.\d+\.\d+)(?:\.([a-z]{2}-[A-Z]{2}))?\.md$/

/** Turn a vX.Y.Z tag into the X.Y.Z version used in file names; throws on any other form. */
export function versionFromTag(tag) {
  const match = /^v(\d+\.\d+\.\d+)$/.exec(tag ?? '')
  if (!match) throw new Error(`Expected a tag like v1.2.3, got "${tag}".`)
  return match[1]
}

/**
 * Validate one notes body. Returns a list of problems (empty when valid).
 * Length is counted in Unicode code points, which is how Play counts characters.
 */
export function problemsInNotes(text) {
  const problems = []
  const body = text.trim()
  if (body === '') problems.push('is empty')
  if ([...body].length > PLAY_NOTES_LIMIT) problems.push(`is ${[...body].length} characters; Play allows ${PLAY_NOTES_LIMIT}`)
  if (/^#/m.test(body)) problems.push('contains a heading; CI adds the title')
  if (/AI disclosure/i.test(body)) problems.push('contains the AI disclosure line; CI appends it')
  return problems
}

/**
 * Check every file in the notes folder: names follow the grammar, each body is
 * valid, and every version has the source file and all siblings.
 * Returns a list of human-readable problems.
 */
export function checkNotesFolder(folder) {
  if (!existsSync(folder)) return [`${folder} does not exist`]

  const problems = []
  const versions = new Map()

  for (const name of readdirSync(folder)) {
    const match = FILE_NAME.exec(name)
    if (!match) {
      problems.push(`${name}: not <version>.md or <version>.<identity>.md`)
      continue
    }

    const [, version, identity = SOURCE_IDENTITY] = match
    if (identity !== SOURCE_IDENTITY && !SIBLING_IDENTITIES.includes(identity)) {
      problems.push(`${name}: ${identity} is not one of the app's wording catalogs`)
      continue
    }

    for (const problem of problemsInNotes(readFileSync(join(folder, name), 'utf8'))) {
      problems.push(`${name}: ${problem}`)
    }

    if (!versions.has(version)) versions.set(version, new Set())
    versions.get(version).add(identity)
  }

  for (const [version, identities] of versions) {
    for (const identity of [SOURCE_IDENTITY, ...SIBLING_IDENTITIES]) {
      if (!identities.has(identity)) problems.push(`${version}: missing ${fileFor(version, identity)}`)
    }
  }

  return problems
}

/** File name for one version and identity. */
function fileFor(version, identity) {
  return identity === SOURCE_IDENTITY ? `${version}.md` : `${version}.${identity}.md`
}

/** Read one version's notes for every catalog; throws if any file is missing or invalid. */
export function readVersionNotes(folder, version) {
  const notes = {}

  for (const identity of [SOURCE_IDENTITY, ...SIBLING_IDENTITIES]) {
    const path = join(folder, fileFor(version, identity))
    if (!existsSync(path)) throw new Error(`Release notes missing: release-notes/${fileFor(version, identity)}. Commit them before tagging.`)

    const text = readFileSync(path, 'utf8').trim()
    const problems = problemsInNotes(text)
    if (problems.length > 0) throw new Error(`release-notes/${fileFor(version, identity)} ${problems.join('; ')}`)
    notes[identity] = text
  }

  return notes
}

/** Write the Play changelog for every listing locale, aliases included. Returns the files written. */
export function writePlayChangelogs(notes, versionCode, metadataDir) {
  if (!/^\d+$/.test(String(versionCode))) throw new Error(`Expected a numeric versionCode, got "${versionCode}".`)

  const byLocale = { ...notes }
  for (const [alias, source] of Object.entries(LISTING_ALIASES)) byLocale[alias] = notes[source]

  const written = []
  for (const [locale, text] of Object.entries(byLocale)) {
    const dir = join(metadataDir, locale, 'changelogs')
    mkdirSync(dir, { recursive: true })
    const path = join(dir, `${versionCode}.txt`)
    writeFileSync(path, `${text}\n`)
    written.push(path)
  }
  return written
}

/** Where people get SonarBridge: it is distributed only through Google Play. */
export const PLAY_LISTING = `https://play.google.com/store/apps/details?id=${PLAY_PACKAGE}`

/**
 * The GitHub Release body: en-US notes, the Google Play link, then the
 * disclosure line. The release carries no APK.
 *
 * @param notes notes by identity, as readVersionNotes returns them.
 */
export function githubReleaseBody(notes) {
  return [
    notes[SOURCE_IDENTITY],
    '',
    '## Get it',
    '',
    `Install SonarBridge from [Google Play](${PLAY_LISTING}).`,
    '',
    DISCLOSURE_LINE,
    ''
  ].join('\n')
}

/** Command-line entry point; exits non-zero with a message on any problem. */
function main(argv) {
  const repoRoot = resolve(fileURLToPath(new URL('..', import.meta.url)))
  const folder = join(repoRoot, 'release-notes')
  const [command, tag, versionCode, metadataDir] = argv

  if (command === 'check') {
    const problems = checkNotesFolder(folder)
    if (problems.length > 0) {
      for (const problem of problems) console.error(`release-notes/${problem}`)
      process.exit(1)
    }
    return
  }

  if (command === 'play') {
    const notes = readVersionNotes(folder, versionFromTag(tag))
    for (const path of writePlayChangelogs(notes, versionCode, resolve(metadataDir ?? ''))) console.log(path)
    return
  }

  if (command === 'github') {
    const version = versionFromTag(tag)
    process.stdout.write(githubReleaseBody(readVersionNotes(folder, version)))
    return
  }

  console.error('Usage: release-notes.mjs check | play vX.Y.Z <versionCode> <metadataDir> | github vX.Y.Z')
  process.exit(2)
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    main(process.argv.slice(2))
  } catch (error) {
    console.error(error.message)
    process.exit(1)
  }
}
