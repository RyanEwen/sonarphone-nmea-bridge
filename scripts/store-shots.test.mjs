/** Store screenshots: exports must match the manifest, the size Play expects, and RGB without alpha. */
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { ALT_IDENTITIES, LAYOUTS, TEXT_LIMITS, pngHeader, problemsInShots, shotHtml } from './store-shots.mjs'

/** A PNG header only: signature plus an IHDR chunk with the given size and colour type. */
function png(width, height, colorType = 2, bitDepth = 8) {
  const bytes = Buffer.alloc(33)
  Buffer.from('89504e470d0a1a0a', 'hex').copy(bytes, 0)
  bytes.writeUInt32BE(13, 8)
  bytes.write('IHDR', 12, 'ascii')
  bytes.writeUInt32BE(width, 16)
  bytes.writeUInt32BE(height, 20)
  bytes[24] = bitDepth
  bytes[25] = colorType
  return bytes
}

/** A manifest entry for a numbered portrait or landscape shot in a set. */
function shot(number, subject, orientation = 'portrait', set = 'phone') {
  return {
    file: `play/screenshots/${set}/${number}-${subject}-dark.png`,
    source: `raw/android/${set}/${number}-${subject}-dark.png`,
    orientation,
    theme: 'dark',
    headline: 'A short\nheadline.',
    caption: 'A one-line caption.',
    alt: Object.fromEntries(ALT_IDENTITIES.map((identity) => [identity, `${subject} view (${identity})`])),
  }
}

/** Fake file access over a map of path to bytes. */
function files(entries) {
  const readFile = (path) => entries[path] ?? null
  const listDir = (folder) => Object.keys(entries).filter((path) => path.startsWith(`${folder}/`)).map((path) => path.slice(folder.length + 1))
  return [readFile, listDir]
}

/** Files for a manifest set whose exports and raw captures are all present and valid. */
function validFiles(shots, set = 'phone') {
  const entries = {}
  for (const entry of shots) {
    entries[entry.source] = png(1080, 2400, 6)
    entries[entry.file] = png(...LAYOUTS[set][entry.orientation].size)
  }
  return entries
}

test('pngHeader reads size and colour type, and rejects other bytes', () => {
  assert.deepEqual(pngHeader(png(1080, 1920)), { width: 1080, height: 1920, bitDepth: 8, colorType: 2 })
  assert.equal(pngHeader(Buffer.from('not a png at all, just some text bytes')), null)
})

test('A complete set in manifest order is valid', () => {
  const shots = [shot('01', 'sonar'), shot('02', 'sonar', 'landscape'), shot('03', 'status')]
  assert.deepEqual(problemsInShots({ play: { phone: shots } }, ...files(validFiles(shots))), [])
})

test('Tablet sets use the tablet sizes', () => {
  const shots = [shot('01', 'sonar', 'portrait', 'tablet'), shot('02', 'sonar', 'landscape', 'tablet')]
  assert.deepEqual(problemsInShots({ play: { tablet: shots } }, ...files(validFiles(shots, 'tablet'))), [])
  assert.deepEqual(LAYOUTS.tablet.portrait.size, [1200, 1920])

  // A phone-sized export in the tablet set is caught.
  const entries = validFiles(shots, 'tablet')
  entries[shots[0].file] = png(1080, 1920)
  assert.ok(problemsInShots({ play: { tablet: shots } }, ...files(entries)).some((problem) => problem.includes('expected 1200 x 1920')))
})

test('Every wording catalog needs alt text', () => {
  const shots = [shot('01', 'sonar'), shot('02', 'status')]
  delete shots[1].alt['ja-JP']
  const problems = problemsInShots({ play: { phone: shots } }, ...files(validFiles(shots)))
  assert.deepEqual(problems, ['play/screenshots/phone/02-status-dark.png: no alt text for ja-JP'])
})

test('Wrong size, alpha, a missing raw capture and a stray file are all reported', () => {
  const shots = [shot('01', 'sonar'), shot('02', 'status')]
  const entries = validFiles(shots)
  entries[shots[0].file] = png(1440, 3213, 6)
  delete entries[shots[1].source]
  entries['play/screenshots/phone/09-old.png'] = png(1080, 1920)

  const problems = problemsInShots({ play: { phone: shots } }, ...files(entries))
  assert.ok(problems.some((problem) => problem.includes('8-bit RGB PNG without alpha')))
  assert.ok(problems.some((problem) => problem.includes('1440 x 3213, expected 1080 x 1920')))
  assert.ok(problems.some((problem) => problem.includes('raw capture')))
  assert.ok(problems.some((problem) => problem.includes('09-old.png')))
})

test('Names follow NN-subject-theme and text stays within the layout', () => {
  const badName = { ...shot('01', 'sonar'), file: 'play/screenshots/phone/sonar.png' }
  const longCaption = { ...shot('02', 'status'), caption: 'x'.repeat(TEXT_LIMITS.caption + 1) }
  const longHeadline = { ...shot('03', 'settings'), headline: 'one\ntwo\nthree' }
  const shots = [badName, longCaption, longHeadline]

  const problems = problemsInShots({ play: { phone: shots } }, ...files(validFiles(shots)))
  assert.ok(problems.some((problem) => problem.includes('sonar.png: name is not')))
  assert.ok(problems.some((problem) => problem.includes('caption must be')))
  assert.ok(problems.some((problem) => problem.includes('headline must be')))
})

test('Play needs at least two screenshots per set', () => {
  const shots = [shot('01', 'sonar')]
  assert.ok(problemsInShots({ play: { phone: shots } }, ...files(validFiles(shots)))[0].includes('2 to 8'))
})

test('The page shows the raw capture and escapes the text', () => {
  const html = shotHtml({ ...shot('01', 'sonar'), headline: 'Fish <here>' }, 'phone', 0, 'file:///w/store-assets')
  assert.ok(html.includes('src="file:///w/store-assets/raw/android/phone/01-sonar-dark.png"'))
  assert.ok(html.includes('Fish &lt;here&gt;'))
  assert.throws(() => shotHtml({ ...shot('01', 'sonar'), orientation: 'square' }, 'phone', 0, ''))
  assert.throws(() => shotHtml(shot('01', 'sonar'), 'watch', 0, ''))
})
