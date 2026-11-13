"""Exercise the local handbook in Chromium and retain measured review evidence."""
import argparse
import hashlib
import json
import re
from pathlib import Path

from playwright.sync_api import sync_playwright


ROOT = Path(__file__).resolve().parents[1]
HTML = ROOT / 'docs/handbooks/html/overview.html'


def contrast_findings(locator):
    pairs = locator.evaluate_all("""nodes => nodes.filter(e => e.getClientRects().length).map(e => {
      const style = getComputedStyle(e); let p = e, background = 'rgb(255,255,255)';
      while (p) {
        const candidate = getComputedStyle(p).backgroundColor;
        if (candidate !== 'rgba(0, 0, 0, 0)' && candidate !== 'transparent') { background = candidate; break; }
        p = p.parentElement;
      }
      return {text:e.textContent.trim().slice(0,45), foreground:style.color, background,
        large:parseFloat(style.fontSize)>=24 || (parseFloat(style.fontSize)>=18.66 && parseInt(style.fontWeight)>=700)};
    })""")
    def luminance(color):
        channels = [float(v) / 255 for v in re.findall(r'[\d.]+', color)[:3]]
        linear = [v / 12.92 if v <= .04045 else ((v + .055) / 1.055) ** 2.4 for v in channels]
        return sum(v * weight for v, weight in zip(linear, (.2126, .7152, .0722)))
    findings = []
    for pair in pairs:
        values = sorted((luminance(pair['foreground']), luminance(pair['background'])))
        ratio = (values[1] + .05) / (values[0] + .05)
        if ratio < (3 if pair['large'] else 4.5):
            findings.append({**pair, 'ratio': round(ratio, 2)})
    return findings


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    findings, checks, errors, requests = [], [], [], []

    def check(name, condition, detail=None):
        checks.append({'name': name, 'pass': bool(condition), 'detail': detail})
        if not condition:
            findings.append({'name': name, 'detail': detail})

    record = json.loads(re.search(r'<script[^>]*id="handbook-record"[^>]*>(.*?)</script>', HTML.read_text(encoding='utf-8'), re.S).group(1))
    source_count = len(record['source_units'])
    slide_count = len(record['slides'])
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(headless=True)
        version = browser.version
        for width, height in [(320, 900), (420, 900), (900, 900), (1440, 1000)]:
            context = browser.new_context(viewport={'width': width, 'height': height}, reduced_motion='reduce')
            # Deliberate API-denial exercise: presentation must remain usable in-window.
            context.add_init_script("Element.prototype.requestFullscreen = () => Promise.reject(new Error('QA fullscreen denied'))")
            page = context.new_page()
            page.on('pageerror', lambda error: errors.append(str(error)))
            page.on('console', lambda message: errors.append(message.text) if message.type == 'error' else None)
            page.on('request', lambda request: requests.append(request.url) if request.url.startswith(('http:', 'https:')) else None)
            page.goto(HTML.as_uri())
            page.wait_for_function("document.documentElement.dataset.dvReady === 'true'")
            prefix = str(width)
            check(prefix + ' reading width', page.evaluate('document.documentElement.scrollWidth <= innerWidth + 1'))
            check(prefix + ' reading title', page.locator('[data-dv-page] h1').count() == 1)
            ids = page.locator('[id]').evaluate_all('(nodes) => nodes.map(node => node.id)')
            check(prefix + ' unique ids', len(ids) == len(set(ids)))
            check(prefix + ' source coverage', page.locator('[data-dv-page] .source-section').count() == source_count)
            options = page.locator('[data-dv-picker] option').count()
            check(prefix + ' chapter options', options == slide_count, options)
            unnamed = page.locator('button,select').evaluate_all("nodes => nodes.filter(n => !(n.getAttribute('aria-label') || n.getAttribute('aria-labelledby') || n.textContent.trim() || n.labels?.length)).length")
            check(prefix + ' control names', unnamed == 0, unnamed)
            page.screenshot(path=str(args.out / f'reading-{width}.png'), full_page=False)
            page.evaluate("document.documentElement.style.fontSize='200%'")
            check(prefix + ' reading at200percent', page.evaluate('document.documentElement.scrollWidth <= innerWidth + 1'))
            if width == 320:
                page.screenshot(path=str(args.out / 'reading-320-200percent.png'))
            page.evaluate("document.documentElement.style.fontSize=''")
            contrast = contrast_findings(page.locator('[data-dv-page] h1,.hero-lede,.eyebrow,.reading-controls label,.reading-controls button'))
            check(prefix + ' reading contrast', not contrast, contrast)
            page.locator('#reading-theme').select_option('dark')
            page.locator('#reading-depth').select_option('overview')
            page.locator('[data-dv-open]').first.click()
            page.wait_for_function('window.NexusDualView.snapshot().active')
            check(prefix + ' global entry starts first', page.evaluate('NexusDualView.snapshot().index === 0'))
            check(prefix + ' fullscreen fallback', page.evaluate('NexusDualView.snapshot().active && !NexusDualView.snapshot().fullscreen'))
            check(prefix + ' reading state independent', page.locator('[data-dv-page]').get_attribute('data-reading-theme') == 'dark')
            for depth in ('concise', 'balanced', 'deep-dive'):
                page.locator('#presentation-depth').select_option(depth)
                for index in range(slide_count):
                    page.locator('[data-dv-picker]').select_option(str(index))
                    page.wait_for_function('(i) => NexusDualView.snapshot().index === i', arg=index)
                    geometry = page.locator('[data-dv-slide]:not([hidden])').evaluate("""slide => {
                      const r = slide.getBoundingClientRect();
                      const stage = slide.parentElement;
                      const controls = document.querySelector('[data-dv-controls]').getBoundingClientRect();
                      const overflow = getComputedStyle(stage).overflowY;
                      return {left:r.left,right:r.right,top:r.top,bottom:r.bottom,width:innerWidth,height:innerHeight,
                        controlsTop:controls.top,controlsBottom:controls.bottom,stageScroll:stage.scrollHeight > stage.clientHeight + 2,
                        scrollAllowed:['auto','scroll'].includes(overflow)};
                    }""")
                    fits = geometry['left'] >= -2 and geometry['right'] <= width + 2 and geometry['controlsBottom'] <= height + 2
                    fits = fits and (geometry['bottom'] <= geometry['controlsTop'] + 2 or geometry['scrollAllowed'])
                    check(f'{prefix} {depth} slide {index + 1} geometry', fits, geometry)
                    contrast = contrast_findings(page.locator('[data-dv-slide]:not([hidden]) > h2,[data-dv-slide]:not([hidden]) > p,[data-dv-slide]:not([hidden]) .slide-points li,[data-dv-controls] button:not(:disabled),[data-dv-controls] label'))
                    check(f'{prefix} {depth} slide {index + 1} contrast', not contrast, contrast)
                    if width == 1440 and depth == 'balanced':
                        page.screenshot(path=str(args.out / f'slide-{index + 1}.png'))
            page.locator('#presentation-depth').select_option('deep-dive')
            check(prefix + ' deep source visible', page.locator('[data-dv-slide]:not([hidden]) .deep-source').is_visible())
            page.locator('#presentation-theme').select_option('light')
            check(prefix + ' presentation theme independent', page.locator('[data-dv-deck]').get_attribute('data-theme') == 'light' and page.locator('[data-dv-page]').get_attribute('data-reading-theme') == 'dark')
            page.keyboard.press('Escape')
            page.wait_for_function('!NexusDualView.snapshot().active')
            check(prefix + ' focus restored', page.locator('[data-dv-open]').first.evaluate('el => el === document.activeElement'))
            page.locator('.hero [data-dv-open]').click()
            page.wait_for_function('NexusDualView.snapshot().active')
            check(prefix + ' second entry resets first', page.evaluate('NexusDualView.snapshot().index === 0'))
            page.locator('#presentation-depth').select_option('balanced')
            page.locator('[data-dv-next]').click()
            check(prefix + ' next control', page.evaluate('NexusDualView.snapshot().index === 1'))
            page.locator('[data-dv-prev]').click()
            check(prefix + ' previous control', page.evaluate('NexusDualView.snapshot().index === 0'))
            page.locator('[data-dv-slide]:not([hidden]) > h2').click()
            page.keyboard.press('End')
            check(prefix + ' keyboard End', page.evaluate('NexusDualView.snapshot().index === 7'))
            page.keyboard.press('Home')
            check(prefix + ' keyboard Home', page.evaluate('NexusDualView.snapshot().index === 0'))
            page.keyboard.press('Tab')
            check(prefix + ' focus stays in presentation', page.evaluate("document.activeElement.closest('[data-dv-deck]') !== null"))
            page.keyboard.press('Escape')
            page.locator('#reading-depth').select_option('full')
            page.emulate_media(media='print')
            check(prefix + ' printable full reading', page.locator('[data-dv-page]').is_visible() and page.locator('[data-dv-page] .technical').is_visible())
            context.close()
        context = browser.new_context(java_script_enabled=False, viewport={'width': 420, 'height': 900})
        page = context.new_page()
        page.goto(HTML.as_uri())
        check('no JavaScript reading', page.locator('[data-dv-page]').is_visible() and page.locator('[data-dv-page] .source-section').count() == source_count)
        visible_entries = sum(page.locator('[data-dv-open],[data-dv-chapter]').nth(i).is_visible() for i in range(page.locator('[data-dv-open],[data-dv-chapter]').count()))
        check('no JavaScript hides inactive controls', visible_entries == 0, visible_entries)
        context.close()
        browser.close()
    check('console and runtime errors', not errors, errors)
    check('offline external requests', not requests, requests)
    report = {'status': 'pass' if not findings else 'fail', 'browser': 'Chromium ' + version,
              'output_sha256': hashlib.sha256(HTML.read_bytes()).hexdigest(),
              'checker_sha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
              'checks': checks, 'findings': findings}
    (args.out / 'render.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
    print(json.dumps({'status': report['status'], 'checks': len(checks), 'findings': findings}, indent=2))
    return 0 if not findings else 1


if __name__ == '__main__':
    raise SystemExit(main())
