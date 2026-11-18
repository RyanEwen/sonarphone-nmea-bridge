/**
 * Play screenshots (LIST-8, ASSET-9): real dark-mode captures placed in a reusable
 * phone frame on the saved studio backdrop. App pixels only ever come from the raw
 * captures in store-assets/raw/; the frame, backdrop and text are added around them.
 * store-assets/manifest.json is the single source of order, text and alt text.
 *
 *   node scripts/store-shots.mjs backdrop
 *       Render store-assets/marketing/backdrop.html to studio-background.png.
 *   node scripts/store-shots.mjs render
 *       Render every manifest entry from its raw capture into store-assets/play/screenshots/.
 *   node scripts/store-shots.mjs check
 *       Validate the exports against the manifest: names, order, size, RGB without alpha.
 *
 * Rendering runs headless Chromium from the Playwright image in a disposable,
 * memory-capped container, so nothing is installed on the host.
 */
import { readFileSync, readdirSync, writeFileSync, mkdirSync, existsSync, rmSync } from 'node:fs'
import { join, resolve, relative } from 'node:path'
import { fileURLToPath } from 'node:url'
import { execFileSync } from 'node:child_process'

/** Current Playwright image; bump deliberately (its Chromium build path below moves with it). */
export const RENDER_IMAGE = 'mcr.microsoft.com/playwright:v1.65.1-noble'
const CHROME = '/ms-playwright/chromium_headless_shell-1255/chrome-headless-shell-linux64/chrome-headless-shell'

/** Container memory cap for one render batch; Chromium needs well under this for a few pages. */
const RENDER_MEMORY = process.env.SONARBRIDGE_RENDER_MEMORY ?? '1500m'

/** CSS pixels times this device scale factor give the export size. */
const SCALE = 1.5

/**
 * Each set and orientation's CSS viewport, export size (ASSET-9: Play phone images
 * at 1080 x 1920 or 16:9, tablet images at 1200 x 1920 or larger) and device frame.
 *
 * `device` places the frame in CSS pixels: its box, the bezel padding (thicker on
 * the camera side), corner radii, and the camera mark. The screen inside the
 * padding keeps the capture's aspect ratio: 20:9 for the phone emulator, 16:10 for
 * the tablet display size.
 */
export const LAYOUTS = {
  phone: {
    portrait: {
      css: [720, 1280], size: [1080, 1920], text: 'large',
      device: { box: [130, 216, 460, 1002], padding: '15px 9px 9px', radius: 46, screenRadius: 32, camera: [202, 6, 56, 3], tilt: 2 },
    },
    landscape: {
      css: [1280, 720], size: [1920, 1080], text: 'small',
      device: { box: [150, 232, 980, 454], padding: '9px 9px 9px 15px', radius: 46, screenRadius: 32, camera: [6, 197, 3, 56], tilt: 1 },
    },
  },
  tablet: {
    portrait: {
      css: [800, 1280], size: [1200, 1920], text: 'large',
      device: { box: [80, 228, 640, 1010], padding: '13px', radius: 34, screenRadius: 20, camera: [314, 4, 6, 6], tilt: 1 },
    },
    landscape: {
      css: [1280, 800], size: [1920, 1200], text: 'small',
      device: { box: [180, 194, 920, 584], padding: '13px', radius: 34, screenRadius: 20, camera: [4, 287, 6, 6], tilt: 1 },
    },
  },
}

/** The layout for one manifest entry in a set; throws on an unknown set or orientation. */
export function layoutFor(set, orientation) {
  const layout = LAYOUTS[set]?.[orientation]
  if (!layout) throw new Error(`Unknown layout "${set}" / "${orientation}".`)
  return layout
}

/** The wording catalogs alt text is written for, matching the app and release notes (LOC-5). */
export const ALT_IDENTITIES = ['en-US', 'es-ES', 'fr-FR', 'de-DE', 'zh-CN', 'pt-BR', 'ja-JP']

/**
 * Text limits that keep the layout intact: a headline line wider than this, or a
 * caption that wraps to a second line, runs into the phone frame.
 */
export const TEXT_LIMITS = { headlineLine: 26, headlineLines: 2, caption: 48 }

/** NN-subject-theme.png (LIST-8): two-digit order, kebab subject, dark or light last. */
const SHOT_NAME = /^(\d{2})-[a-z0-9]+(?:-[a-z0-9]+)*-(dark|light)\.png$/

/** Escape text before it is placed in generated HTML. */
function escapeHtml(value) {
  return String(value).replace(/[&<>"']/g, (character) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[character])
}

/**
 * Read a PNG's header. Returns { width, height, bitDepth, colorType }, or null when
 * the bytes are not a PNG with a complete IHDR chunk.
 */
export function pngHeader(bytes) {
  const signature = '89504e470d0a1a0a'
  if (bytes.length < 33 || bytes.subarray(0, 8).toString('hex') !== signature) return null
  if (bytes.toString('ascii', 12, 16) !== 'IHDR') return null
  return {
    width: bytes.readUInt32BE(16),
    height: bytes.readUInt32BE(20),
    bitDepth: bytes[24],
    colorType: bytes[25],
  }
}

/**
 * The HTML page for one screenshot. The capture is shown whole (object-fit:
 * contain) inside a CSS device frame, tilted slightly and alternating by position
 * as persistent's carousel does, below an English headline and caption (D-098).
 *
 * @param shot a manifest entry: source, orientation, headline and caption.
 * @param set 'phone' or 'tablet', which picks the frame.
 * @param index the entry's position in its set, which picks the tilt direction.
 * @param assetsUrl file URL of store-assets/ as the container sees it.
 */
export function shotHtml(shot, set, index, assetsUrl) {
  const { css: [width, height], device, text } = layoutFor(set, shot.orientation)
  const [left, top, boxWidth, boxHeight] = device.box
  const [cameraLeft, cameraTop, cameraWidth, cameraHeight] = device.camera
  const angle = index % 2 === 0 ? -device.tilt : device.tilt
  const large = text === 'large'

  return `<!doctype html>
<html lang="en"><meta charset="utf-8"><title>SonarBridge store composition</title>
<style>
* { box-sizing: border-box; }
html, body { margin: 0; width: ${width}px; height: ${height}px; overflow: hidden; }
body { background: #0b1d2c url('${assetsUrl}/marketing/studio-background.png') center/cover; color: #eef4f8; font-family: 'Liberation Sans', Arial, sans-serif; }
.copy { position: absolute; top: ${large ? 46 : 34}px; left: 60px; right: 60px; z-index: 2; }
h1 { white-space: pre-line; margin: 0; font-size: ${large ? 48 : 40}px; line-height: 1.06; letter-spacing: -1.2px; }
p { margin: ${large ? 15 : 10}px 0 0; color: #a9bfd0; font-size: 22px; line-height: 1.35; }
.device { position: absolute; left: ${left}px; top: ${top}px; width: ${boxWidth}px; height: ${boxHeight}px;
  padding: ${device.padding}; border-radius: ${device.radius}px; transform: rotate(${angle}deg);
  background: linear-gradient(110deg, #5c6672, #10151b 12%, #131920 88%, #6f7a87);
  border: 2px solid #5f6b78; box-shadow: 20px 26px 44px #00000080, 4px 6px 10px #00000060, inset 0 0 0 3px #a2acb930; }
.device:before { content: ''; position: absolute; left: ${cameraLeft}px; top: ${cameraTop}px; width: ${cameraWidth}px; height: ${cameraHeight}px;
  border-radius: 8px; background: #0b1016; }
.screen { width: 100%; height: 100%; overflow: hidden; border-radius: ${device.screenRadius}px; background: #000; }
img { width: 100%; height: 100%; object-fit: contain; display: block; }
</style>
<div class="copy"><h1>${escapeHtml(shot.headline)}</h1><p>${escapeHtml(shot.caption)}</p></div>
<div class="device"><div class="screen"><img src="${assetsUrl}/${escapeHtml(shot.source)}" alt=""></div></div>
</html>
`
}

/** Every manifest entry with its set name ('phone' or 'tablet') attached. */
function manifestShots(manifest) {
  return Object.entries(manifest.play ?? {}).flatMap(([set, shots]) => shots.map((shot, index) => ({ ...shot, set, index })))
}

/**
 * Check the exports against the manifest. Returns a list of problems (empty when
 * valid). `readFile(path)` returns a Buffer or null, and `listDir(path)` returns
 * the names in a folder, so the logic can be tested without real files.
 */
export function problemsInShots(manifest, readFile, listDir) {
  const problems = []

  for (const [set, shots] of Object.entries(manifest.play ?? {})) {
    if (!Array.isArray(shots) || shots.length < 2 || shots.length > 8) problems.push(`${set}: Play takes 2 to 8 screenshots, the manifest lists ${shots?.length ?? 0}`)

    // The upload folder holds exactly the manifest's files, in the manifest's order.
    const listed = (shots ?? []).map((shot) => shot.file.split('/').at(-1))
    const present = listDir(`play/screenshots/${set}`).filter((name) => name.endsWith('.png')).sort()
    if (present.join() !== listed.join()) problems.push(`${set}: folder holds ${present.join(', ') || 'nothing'}, manifest orders ${listed.join(', ')}`)

    for (const shot of shots ?? []) {
      const name = shot.file.split('/').at(-1)
      if (!SHOT_NAME.test(name)) problems.push(`${shot.file}: name is not NN-subject-dark.png or NN-subject-light.png`)
      if (!name.endsWith(`-${shot.theme}.png`)) problems.push(`${shot.file}: theme "${shot.theme}" does not match the name`)
      const missingAlt = ALT_IDENTITIES.filter((identity) => !shot.alt?.[identity])
      if (missingAlt.length > 0) problems.push(`${shot.file}: no alt text for ${missingAlt.join(', ')}`)
      const lines = String(shot.headline ?? '').split('\n')
      if (!shot.headline || lines.length > TEXT_LIMITS.headlineLines || lines.some((line) => [...line].length > TEXT_LIMITS.headlineLine)) {
        problems.push(`${shot.file}: headline must be 1 or 2 lines of up to ${TEXT_LIMITS.headlineLine} characters`)
      }
      if (!shot.caption || [...shot.caption].length > TEXT_LIMITS.caption) problems.push(`${shot.file}: caption must be 1 to ${TEXT_LIMITS.caption} characters`)
      if (!readFile(shot.source)) problems.push(`${shot.file}: raw capture ${shot.source} is missing`)

      const layout = LAYOUTS[set]?.[shot.orientation]
      if (!layout) {
        problems.push(`${shot.file}: unknown layout "${set}" / "${shot.orientation}"`)
        continue
      }

      const bytes = readFile(shot.file)
      const header = bytes ? pngHeader(bytes) : null
      if (!header) {
        problems.push(`${shot.file}: missing or not a PNG`)
        continue
      }
      if (header.bitDepth !== 8 || header.colorType !== 2) problems.push(`${shot.file}: export an 8-bit RGB PNG without alpha`)
      if (header.width !== layout.size[0] || header.height !== layout.size[1]) {
        problems.push(`${shot.file}: ${header.width} x ${header.height}, expected ${layout.size.join(' x ')}`)
      }
    }
  }

  return problems
}

/**
 * Screenshot each page in one disposable, memory-capped Chromium container.
 *
 * @param repoRoot the repository root, mounted at /w.
 * @param jobs [{ page, output, css }] with paths relative to repoRoot.
 */
function renderPages(repoRoot, jobs) {
  const commands = jobs.map(({ page, output, css }) => [
    CHROME, '--no-sandbox', '--disable-gpu', '--hide-scrollbars', '--allow-file-access-from-files',
    `--force-device-scale-factor=${SCALE}`, `--window-size=${css[0]},${css[1]}`,
    // Give the backdrop and capture time to decode before the screenshot.
    '--virtual-time-budget=2000',
    `--screenshot=/w/${output}`, `file:///w/${page}`,
  ].map((part) => `'${part}'`).join(' '))

  execFileSync('docker', [
    'run', '--rm', `--memory=${RENDER_MEMORY}`, `--memory-swap=${RENDER_MEMORY}`,
    '--user', `${process.getuid()}:${process.getgid()}`, '-e', 'HOME=/tmp',
    '-v', `${repoRoot}:/w`, RENDER_IMAGE,
    'bash', '-euo', 'pipefail', '-c', commands.map((command) => `${command} 2>/dev/null`).join('\n'),
  ], { stdio: 'inherit' })
}

/** Command-line entry point; exits non-zero with a message on any problem. */
function main([command]) {
  const repoRoot = resolve(fileURLToPath(new URL('..', import.meta.url)))
  const assets = join(repoRoot, 'store-assets')
  const manifest = JSON.parse(readFileSync(join(assets, 'manifest.json'), 'utf8'))

  if (command === 'backdrop') {
    renderPages(repoRoot, [{
      page: 'store-assets/marketing/backdrop.html',
      output: 'store-assets/marketing/studio-background.png',
      css: [1280, 1280],
    }])
    return
  }

  if (command === 'render') {
    // Generated pages are scratch output; the manifest and raw captures are the source.
    const pages = join(repoRoot, '.artifacts', 'store-shots')
    rmSync(pages, { recursive: true, force: true })
    mkdirSync(pages, { recursive: true })

    // Never render a frame around a missing capture.
    const shots = manifestShots(manifest)
    const missing = shots.filter((shot) => !existsSync(join(assets, shot.source))).map((shot) => shot.source)
    if (missing.length > 0) throw new Error(`Raw captures missing: ${missing.join(', ')}`)

    const jobs = shots.map((shot) => {
      mkdirSync(join(assets, 'play', 'screenshots', shot.set), { recursive: true })
      const page = join(pages, `${shot.set}-${shot.file.split('/').at(-1).replace(/\.png$/, '.html')}`)
      writeFileSync(page, shotHtml(shot, shot.set, shot.index, 'file:///w/store-assets'))
      return { page: relative(repoRoot, page), output: `store-assets/${shot.file}`, css: layoutFor(shot.set, shot.orientation).css }
    })
    renderPages(repoRoot, jobs)
    for (const job of jobs) console.log(job.output)
    return
  }

  if (command === 'check') {
    const readFile = (path) => (existsSync(join(assets, path)) ? readFileSync(join(assets, path)) : null)
    const listDir = (path) => (existsSync(join(assets, path)) ? readdirSync(join(assets, path)) : [])
    const problems = problemsInShots(manifest, readFile, listDir)
    if (problems.length > 0) {
      for (const problem of problems) console.error(problem)
      process.exit(1)
    }
    return
  }

  console.error('Usage: store-shots.mjs backdrop | render | check')
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
