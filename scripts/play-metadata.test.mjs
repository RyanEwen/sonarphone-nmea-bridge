/** The Play listing has one source; the fastlane tree is generated from it with aliases expanded. */
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, readdirSync, rmSync } from 'node:fs'
import { join } from 'node:path'
import { tmpdir } from 'node:os'
import { LISTING_LOCALES, buildFastlaneTree, fastlaneScreenshotName, problemsInListings } from './play-metadata.mjs'

/** A minimal valid listings.json, with en-CA and fr-CA as aliases. */
function listings(overrides = {}) {
  const locales = {}
  for (const locale of LISTING_LOCALES) {
    locales[locale] = { title: 'SonarBridge', shortDescription: `Short ${locale}`, fullDescription: `Full ${locale}` }
  }
  locales['en-CA'] = { copyFrom: 'en-US' }
  locales['fr-CA'] = { copyFrom: 'fr-FR' }
  return { schemaVersion: 1, locales: { ...locales, ...overrides } }
}

test('A complete listing with aliases is valid', () => {
  assert.deepEqual(problemsInListings(listings()), [])
})

test('Over-long fields, missing fields, chained aliases and missing locales are rejected', () => {
  const problems = problemsInListings(listings({
    'en-US': { title: 'x'.repeat(31), shortDescription: 'ok', fullDescription: '' },
    'fr-CA': { copyFrom: 'en-CA' }
  })).join('\n')
  assert.match(problems, /en-US\.title is 31 characters; Play allows 30/)
  assert.match(problems, /en-US\.fullDescription is missing/)
  assert.match(problems, /fr-CA: copyFrom must name a full locale record/)

  const missing = listings()
  delete missing.locales['ja-JP']
  assert.match(problemsInListings(missing).join(), /locales must be exactly/)
})

test('Screenshot names map to fastlane order and naming', () => {
  assert.equal(fastlaneScreenshotName('02-sonar-landscape.png'), '2_sonar_landscape.png')
  assert.throws(() => fastlaneScreenshotName('sonar.png'))
})

test('The generated tree expands aliases and places images once, under en-US', () => {
  const source = mkdtempSync(join(tmpdir(), 'play-source-'))
  const out = mkdtempSync(join(tmpdir(), 'play-out-'))
  try {
    writeFileSync(join(source, 'listings.json'), JSON.stringify(listings()))
    writeFileSync(join(source, 'icon.png'), 'icon')
    writeFileSync(join(source, 'feature-graphic.png'), 'feature')
    mkdirSync(join(source, 'screenshots', 'phone'), { recursive: true })
    writeFileSync(join(source, 'screenshots', 'phone', '01-sonar.png'), 'shot')

    buildFastlaneTree(source, out)

    assert.deepEqual(readdirSync(out).sort(), [...LISTING_LOCALES].sort())
    assert.equal(readFileSync(join(out, 'en-CA', 'short_description.txt'), 'utf8'), 'Short en-US\n')
    assert.equal(readFileSync(join(out, 'fr-CA', 'full_description.txt'), 'utf8'), 'Full fr-FR\n')
    assert.equal(readFileSync(join(out, 'en-US', 'images', 'featureGraphic.png'), 'utf8'), 'feature')
    assert.equal(readFileSync(join(out, 'en-US', 'images', 'phoneScreenshots', '1_sonar.png'), 'utf8'), 'shot')
    assert.deepEqual(readdirSync(join(out, 'fr-FR')).sort(), ['full_description.txt', 'short_description.txt', 'title.txt'])
  } finally {
    rmSync(source, { recursive: true })
    rmSync(out, { recursive: true })
  }
})
