/** Release notes are committed, complete for every catalog, within Play's limit, and never generated. */
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, writeFileSync, readFileSync, rmSync } from 'node:fs'
import { join } from 'node:path'
import { tmpdir } from 'node:os'
import {
  SIBLING_IDENTITIES, PLAY_NOTES_LIMIT, DISCLOSURE_LINE,
  PLAY_LISTING, checkNotesFolder, githubReleaseBody, problemsInNotes, readVersionNotes, versionFromTag, writePlayChangelogs
} from './release-notes.mjs'

/** A temporary notes folder holding one complete version, with optional overrides. */
function folderWith(files = {}) {
  const dir = mkdtempSync(join(tmpdir(), 'release-notes-'))
  const complete = { '1.2.3.md': '- Faster reconnects.' }
  for (const identity of SIBLING_IDENTITIES) complete[`1.2.3.${identity}.md`] = `- ${identity} text.`
  for (const [name, text] of Object.entries({ ...complete, ...files })) {
    if (text !== null) writeFileSync(join(dir, name), text)
  }
  return dir
}

test('A complete version passes the folder check', () => {
  const dir = folderWith()
  try {
    assert.deepEqual(checkNotesFolder(dir), [])
  } finally {
    rmSync(dir, { recursive: true })
  }
})

test('A missing sibling, a stray identity or a bad name fails the check', () => {
  const dir = folderWith({ '1.2.3.ja-JP.md': null, '1.2.3.it-IT.md': '- x', 'notes.md': '- x' })
  try {
    const problems = checkNotesFolder(dir).join('\n')
    assert.match(problems, /missing 1\.2\.3\.ja-JP\.md/)
    assert.match(problems, /it-IT is not one of the app's wording catalogs/)
    assert.match(problems, /notes\.md: not <version>/)
  } finally {
    rmSync(dir, { recursive: true })
  }
})

test('Over-long, empty, headed or disclosure-carrying notes are rejected, never truncated', () => {
  assert.deepEqual(problemsInNotes('é'.repeat(PLAY_NOTES_LIMIT)), [])
  assert.match(problemsInNotes('é'.repeat(PLAY_NOTES_LIMIT + 1)).join(), /501 characters/)
  assert.match(problemsInNotes('  \n').join(), /empty/)
  assert.match(problemsInNotes('## What changed\n- x').join(), /heading/)
  assert.match(problemsInNotes(`- x\n${DISCLOSURE_LINE}`).join(), /disclosure/)
})

test('Reading a tagged version fails when its notes are not committed', () => {
  const dir = folderWith()
  try {
    assert.throws(() => readVersionNotes(dir, '1.2.4'), /release-notes\/1\.2\.4\.md/)
    assert.equal(readVersionNotes(dir, '1.2.3')['en-US'], '- Faster reconnects.')
  } finally {
    rmSync(dir, { recursive: true })
  }
})

test('Play changelogs cover every listing locale, with Canadian aliases copied', () => {
  const dir = folderWith()
  const out = mkdtempSync(join(tmpdir(), 'play-metadata-'))
  try {
    const written = writePlayChangelogs(readVersionNotes(dir, '1.2.3'), 10203, out)
    assert.equal(written.length, SIBLING_IDENTITIES.length + 3)
    assert.equal(readFileSync(join(out, 'en-CA/changelogs/10203.txt'), 'utf8'), '- Faster reconnects.\n')
    assert.equal(readFileSync(join(out, 'fr-CA/changelogs/10203.txt'), 'utf8'), '- fr-FR text.\n')
  } finally {
    rmSync(dir, { recursive: true })
    rmSync(out, { recursive: true })
  }
})

test('The GitHub body is the en-US notes followed by the disclosure line', () => {
  const body = githubReleaseBody({ 'en-US': '- Faster reconnects.' }, '1.2.3')
  assert.ok(body.startsWith('- Faster reconnects.\n'))
  assert.ok(body.trimEnd().endsWith(DISCLOSURE_LINE))
})

test('The GitHub body links to Google Play and offers no APK', () => {
  const body = githubReleaseBody({ 'en-US': '- Faster reconnects.' })
  assert.ok(body.includes(`(${PLAY_LISTING})`))
  assert.ok(!/\.apk/i.test(body))
})

test('Only vX.Y.Z tags name a version', () => {
  assert.equal(versionFromTag('v0.2.7'), '0.2.7')
  for (const tag of ['0.2.7', 'v0.2', 'desktop-v1.0.0', undefined]) assert.throws(() => versionFromTag(tag))
})
