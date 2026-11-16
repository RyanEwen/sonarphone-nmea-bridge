/**
 * The Play listing source is store-assets/play/ (LIST-3, LIST-6): listings.json for
 * the text and one copy of each store image. fastlane's metadata tree is generated
 * from it in CI and never committed or edited by hand.
 *
 *   node scripts/play-metadata.mjs check
 *       Validate listings.json: every listing locale, aliases, and Play's length limits.
 *   node scripts/play-metadata.mjs build <outDir>
 *       Write the fastlane tree (<outDir>/<locale>/*.txt and en-US/images/) for `fastlane supply`.
 */
import { readFileSync, readdirSync, mkdirSync, writeFileSync, copyFileSync, existsSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

/** The listing locales, in the order locales.md gives (LIST-7: the same set as the app). */
export const LISTING_LOCALES = ['en-US', 'en-CA', 'es-ES', 'fr-FR', 'fr-CA', 'de-DE', 'zh-CN', 'pt-BR', 'ja-JP']

/** Play's limits, counted in characters (LIST-5). */
export const PLAY_LIMITS = { title: 30, shortDescription: 80, fullDescription: 4000 }

/** listings.json field name to the fastlane file it becomes. */
const FASTLANE_FILES = { title: 'title.txt', shortDescription: 'short_description.txt', fullDescription: 'full_description.txt' }

/** Screenshot folders in store-assets/play/screenshots to fastlane's folder names. */
const SCREENSHOT_FOLDERS = { phone: 'phoneScreenshots', tablet: 'tenInchScreenshots' }

/** Return a list of problems with a parsed listings.json (empty when valid). */
export function problemsInListings(listings) {
  const problems = []
  if (listings?.schemaVersion !== 1) problems.push('schemaVersion must be 1')

  const locales = listings?.locales ?? {}
  const keys = Object.keys(locales)
  if (keys.join() !== LISTING_LOCALES.join()) problems.push(`locales must be exactly ${LISTING_LOCALES.join(', ')} in that order`)

  for (const [locale, record] of Object.entries(locales)) {
    if ('copyFrom' in record) {
      const source = locales[record.copyFrom]
      if (!source || 'copyFrom' in source) problems.push(`${locale}: copyFrom must name a full locale record`)
      if (Object.keys(record).length !== 1) problems.push(`${locale}: an alias holds only copyFrom`)
      continue
    }

    for (const [field, limit] of Object.entries(PLAY_LIMITS)) {
      const value = record[field]
      if (typeof value !== 'string' || value.trim() === '') {
        problems.push(`${locale}.${field} is missing`)
      } else if ([...value].length > limit) {
        problems.push(`${locale}.${field} is ${[...value].length} characters; Play allows ${limit}`)
      }
    }
  }

  return problems
}

/** Expand aliases so every listing locale has a full record. */
export function expandListings(listings) {
  const expanded = {}
  for (const locale of LISTING_LOCALES) {
    const record = listings.locales[locale]
    expanded[locale] = 'copyFrom' in record ? listings.locales[record.copyFrom] : record
  }
  return expanded
}

/** fastlane's screenshot name for a source file: 02-sonar-landscape.png becomes 2_sonar_landscape.png. */
export function fastlaneScreenshotName(fileName) {
  const match = /^(\d+)-(.+)\.png$/.exec(fileName)
  if (!match) throw new Error(`Screenshot names are NN-subject.png, got "${fileName}".`)
  return `${Number(match[1])}_${match[2].replaceAll('-', '_')}.png`
}

/**
 * Write the fastlane metadata tree for `fastlane supply` into outDir.
 * Text goes to every listing locale; images go once, to the default en-US listing.
 */
export function buildFastlaneTree(sourceDir, outDir) {
  const listings = JSON.parse(readFileSync(join(sourceDir, 'listings.json'), 'utf8'))
  const problems = problemsInListings(listings)
  if (problems.length > 0) throw new Error(`store-assets/play/listings.json: ${problems.join('; ')}`)

  // Listing text, one folder per locale.
  for (const [locale, record] of Object.entries(expandListings(listings))) {
    const dir = join(outDir, locale)
    mkdirSync(dir, { recursive: true })
    for (const [field, file] of Object.entries(FASTLANE_FILES)) writeFileSync(join(dir, file), `${record[field]}\n`)
  }

  // Store graphics and screenshots, captured once in English (D-098).
  const images = join(outDir, 'en-US', 'images')
  mkdirSync(images, { recursive: true })
  copyFileSync(join(sourceDir, 'icon.png'), join(images, 'icon.png'))
  copyFileSync(join(sourceDir, 'feature-graphic.png'), join(images, 'featureGraphic.png'))
  for (const [folder, fastlaneFolder] of Object.entries(SCREENSHOT_FOLDERS)) {
    const from = join(sourceDir, 'screenshots', folder)
    if (!existsSync(from)) continue
    mkdirSync(join(images, fastlaneFolder), { recursive: true })
    for (const file of readdirSync(from).sort()) copyFileSync(join(from, file), join(images, fastlaneFolder, fastlaneScreenshotName(file)))
  }
}

/** Command-line entry point; exits non-zero with a message on any problem. */
function main(argv) {
  const sourceDir = resolve(fileURLToPath(new URL('../store-assets/play', import.meta.url)))
  const [command, outDir] = argv

  if (command === 'check') {
    const problems = problemsInListings(JSON.parse(readFileSync(join(sourceDir, 'listings.json'), 'utf8')))
    if (problems.length > 0) {
      for (const problem of problems) console.error(`store-assets/play/listings.json: ${problem}`)
      process.exit(1)
    }
    return
  }

  if (command === 'build' && outDir) {
    buildFastlaneTree(sourceDir, resolve(outDir))
    return
  }

  console.error('Usage: play-metadata.mjs check | build <outDir>')
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
