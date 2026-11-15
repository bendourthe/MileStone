"""Exercise the local handbook in Chromium and retain measured review evidence."""
import argparse
import hashlib
import json
import re
from html import unescape
from pathlib import Path

from playwright.sync_api import TimeoutError as PlaywrightTimeoutError, sync_playwright


ROOT = Path(__file__).resolve().parents[1]
HTML = ROOT / 'docs/handbooks/html/overview.html'
MODEL = ROOT / 'docs/handbooks/_sources/overview/model.json'
VIEWPORTS = [(320, 900), (390, 844), (420, 900), (759, 844), (760, 844),
             (761, 844), (900, 900), (999, 900), (1000, 900), (1001, 900),
             (1024, 640), (1200, 800), (1366, 768), (1440, 1000),
             (1920, 1080), (2560, 1300)]


def plain_inline(text):
    """Read the retained Markdown vocabulary without invoking its renderer."""
    code = []
    def protect(match):
        code.append(match[1])
        return f'\x00{len(code) - 1}\x00'
    text = re.sub(r'`([^`]+)`', protect, text)
    text = re.sub(r'\[([^\]]+)\]\([^\s)]+\)', r'\1', text)
    text = re.sub(r'\*\*([^*]+)\*\*', r'\1', text)
    text = re.sub(r'(?<!\*)\*([^*]+)\*(?!\*)', r'\1', text)
    for index, value in enumerate(code):
        text = text.replace(f'\x00{index}\x00', value)
    return ' '.join(unescape(text).split())


def independent_inventory():
    model = json.loads(MODEL.read_text(encoding='utf-8'))
    units, values = [], []
    feature_text = []
    for source in model['sources']:
        text = (ROOT / source['path']).read_text(encoding='utf-8-sig')
        feature_text.append(text)
        lines, index, unit, counters = text.splitlines(), 0, None, {}
        while index < len(lines):
            line = lines[index]
            index += 1
            if not line.strip():
                continue
            heading = re.fullmatch(r'(#{1,2}) (.+)', line)
            if heading:
                unit = source['id'] + '-' + re.sub(r'[^a-z0-9]+', '-', heading[2].lower()).strip('-')
                units.append(unit)
                counters = {}
                continue
            if unit is None:
                raise ValueError('Source content before a retained heading')
            if line.startswith('```'):
                block = []
                while index < len(lines) and not lines[index].startswith('```'):
                    block.append(lines[index])
                    index += 1
                index += 1
                blocks = [('pre code', ' '.join('\n'.join(block).split()))]
            elif line.startswith('|'):
                if re.fullmatch(r'[| :\-]+', line):
                    continue
                blocks = [('th,td', plain_inline(cell.strip())) for cell in line.strip().strip('|').split('|')]
            elif re.match(r'^(?:- |\d+\. )', line):
                blocks = [('li', plain_inline(re.sub(r'^(?:- |\d+\. )', '', line)))]
            else:
                block = [line]
                while index < len(lines) and lines[index].strip() and not re.match(r'^(#|```|\||- |\d+\. )', lines[index]):
                    block.append(lines[index])
                    index += 1
                blocks = [('p', plain_inline(' '.join(block)))]
            for selector, expected in blocks:
                offset = counters.get(selector, 0)
                counters[selector] = offset + 1
                values.append({'unit': unit, 'selector': selector, 'offset': offset,
                               'text': expected, 'numbers': re.findall(r'\d+(?:[.,:/-]\d+)*', expected)})
    template = (MODEL.parent / 'template.html.in').read_text(encoding='utf-8')
    return {'model': model, 'units': units, 'source_values': values,
            'control_behaviors': ['two global entries reset first', 'each source chapter targets mapped slide',
                'previous/next and boundaries', 'chapter picker', 'reading themes/detail',
                'presentation themes/detail', 'replay restarts motion', 'fullscreen entry/exit/denial',
                'return to reading restores focus/scroll', 'hash and browser history',
                'native select/scroll-region key ownership'],
            'source_features': {'markdown_images': sum(len(re.findall(r'!\[[^\]]*\]\(', t)) for t in feature_text),
                'authored_figures': len(re.findall(r'<(?:figure|canvas|img)\b', template)),
                'nested_dialogs': len(re.findall(r'<dialog\b', template)),
                'editable_inputs': len(re.findall(r'<(?:input|textarea)\b|contenteditable=', template))}}


GEOMETRY = r"""slide => {
  const stage = slide.parentElement, sr = stage.getBoundingClientRect();
  const r = slide.getBoundingClientRect(), controls = document.querySelector('[data-dv-controls]').getBoundingClientRect();
  const scrolling = e => ['auto','scroll'].includes(getComputedStyle(e).overflowY) || ['auto','scroll'].includes(getComputedStyle(e).overflowX);
  const visible = e => e.getClientRects().length && !e.closest('[hidden]') && getComputedStyle(e).visibility !== 'hidden';
  const escapes = [], text = []; let totalEscapes = 0;
  for (const e of slide.querySelectorAll('*')) {
    if (!visible(e)) continue;
    const box = e.getBoundingClientRect();
    let parent = e.parentElement, localScroll = false;
    while (parent && parent !== stage) {
      if (scrolling(parent)) { localScroll = true; break; }
      parent = parent.parentElement;
    }
    if (localScroll) continue;
    const overflow = box.left < sr.left - 2 || box.right > sr.right + 2 ||
      (!scrolling(stage) && (box.top < sr.top - 2 || box.bottom > sr.bottom + 2));
    if (overflow) { totalEscapes++; if (escapes.length < 12) escapes.push({tag:e.tagName,text:e.textContent.trim().slice(0,75),left:box.left,right:box.right,top:box.top,bottom:box.bottom}); }
  }
  const walker = document.createTreeWalker(slide, NodeFilter.SHOW_TEXT);
  let node;
  while (node = walker.nextNode()) {
    const e = node.parentElement;
    if (!node.textContent.trim() || !visible(e)) continue;
    const range = document.createRange(); range.selectNodeContents(node);
    if (!range.getClientRects().length) continue;
    let scale = 1, ancestor = e;
    while (ancestor && ancestor !== stage.parentElement) {
      const transform = getComputedStyle(ancestor).transform;
      if (transform !== 'none') { const matrix = new DOMMatrixReadOnly(transform); scale *= Math.hypot(matrix.c,matrix.d); }
      ancestor = ancestor.parentElement;
    }
    const px = parseFloat(getComputedStyle(e).fontSize) * scale;
    text.push({text:node.textContent.trim().slice(0,70),px:Math.round(px*100)/100});
  }
  const floor = stage.clientHeight * .02;
  return {left:r.left,right:r.right,top:r.top,bottom:r.bottom,width:innerWidth,height:innerHeight,
    controlsTop:controls.top,controlsBottom:controls.bottom,stageHeight:stage.clientHeight,
    stageScroll:stage.scrollHeight>stage.clientHeight+2,scrollAllowed:scrolling(stage),
    descendantEscapeCount:totalEscapes,descendantEscapes:escapes,stageFontFloor:floor,
    fontSizes:[...new Set(text.map(t=>t.px))],belowStageFloor:text.filter(t=>t.px+.05<floor).slice(0,12),
    minimumFont:text.length?Math.min(...text.map(t=>t.px)):null};
}"""


def contract_checks(browser, inventory, check, out, errors, requests):
    """Observe current project vocabulary, not the incompatible helper schema."""
    model = inventory['model']
    context = browser.new_context(viewport={'width': 1366, 'height': 768}, reduced_motion='no-preference')
    page = context.new_page()
    page.set_default_timeout(5000)
    page.on('pageerror', lambda error: errors.append(str(error)))
    page.on('console', lambda message: errors.append(message.text) if message.type == 'error' else None)
    page.on('request', lambda request: requests.append(request.url) if request.url.startswith(('http:', 'https:')) else None)
    page.goto(HTML.as_uri())
    page.wait_for_function("document.documentElement.dataset.dvReady === 'true'")
    page.evaluate('document.fonts.ready')
    feature = page.evaluate("""() => ({figures:document.querySelectorAll('figure,canvas,img').length,
      nestedDialogs:document.querySelectorAll('[data-dv-deck] dialog').length,
      editable:document.querySelectorAll('input,textarea,[contenteditable]').length,
      svg:document.querySelectorAll('svg').length,
      semanticSvg:[...document.querySelectorAll('svg')].filter(e=>!e.closest('[data-dv-controls]')).length})""")
    inventory['observed_features'] = feature
    expected_controls = ['[data-dv-prev]','[data-dv-next]','[data-dv-picker]',
        '#presentation-theme','#presentation-depth','[data-dv-replay]',
        '[data-dv-fullscreen]','[data-dv-exit]']
    inventory['presentation_control_selectors'] = expected_controls
    check('complete authored presentation control inventory',
        page.locator('[data-dv-deck] button,[data-dv-deck] select').count() == len(expected_controls)
        and all(page.locator(selector).count() == 1 for selector in expected_controls))
    inventory['applicability'] = {'charts_maps_photos': 'not applicable: no authored/observed figure, canvas or image' if not feature['figures'] and not inventory['source_features']['markdown_images'] else 'requires review',
        'nested_modals': 'not applicable: no authored/observed nested dialog' if not feature['nestedDialogs'] else 'requires review',
        'editable_inputs': 'not applicable: no authored/observed editable input; native selects/scroll region checked' if not feature['editable'] else 'requires review',
        'connector_routing': 'not applicable: all observed SVGs are action icons inside presentation controls; no source figure/diagram' if not feature['semanticSvg'] and not feature['figures'] else 'requires review'}
    check('independent source inventory', page.locator('[data-dv-page] [data-source-unit]').evaluate_all('(es)=>es.map(e=>e.dataset.sourceUnit)') == inventory['units'])
    expected_slides = [{k: s[k] for k in ('id','theme','units')} for s in model['slides']]
    check('canonical record storyboard equals retained model', json.loads(page.locator('#handbook-record').text_content())['slides'] == expected_slides)
    check('canonical storyboard DOM themes and ceiling', page.locator('[data-dv-slide]').evaluate_all('(es)=>es.map(e=>({id:e.dataset.dvSlide,theme:e.dataset.defaultTheme}))') == [{'id':s['id'],'theme':s['theme']} for s in model['slides']] and len(expected_slides) <= model['presentation']['slide_ceiling'])
    for value in inventory['source_values']:
        actual = page.locator(f'[data-dv-page] [data-source-unit="{value["unit"]}"]').locator(value['selector']).nth(value['offset']).text_content()
        check('source text ' + value['unit'] + ' ' + value['selector'] + str(value['offset']), ' '.join(actual.split()) == value['text'])
    # Chapters are expected from the retained model, not inferred from delivered attributes.
    for unit in inventory['units']:
        expected = next(i for i,s in enumerate(model['slides']) if unit in s['units'])
        button = page.locator(f'[data-dv-page] [data-source-unit="{unit}"] [data-dv-chapter]')
        button.scroll_into_view_if_needed()
        reading_scroll = page.evaluate('scrollY')
        button.click()
        page.wait_for_function('(i)=>NexusDualView.snapshot().index===i', arg=expected)
        check('chapter click ' + unit, page.evaluate('NexusDualView.snapshot().active') and page.locator('[data-dv-slide]:not([hidden])').get_attribute('data-dv-slide') == model['slides'][expected]['id'])
        page.locator('[data-dv-exit]').click()
        page.wait_for_function('!NexusDualView.snapshot().active')
        try:
            page.wait_for_function('(button)=>button===document.activeElement', arg=button.element_handle(), timeout=3000)
        except PlaywrightTimeoutError:
            check('chapter bounded focus wait ' + unit, False, 'Focus did not return within 3000ms')
        check('chapter focus return ' + unit, button.evaluate('e=>e===document.activeElement'))
        check('chapter reading scroll return ' + unit, abs(page.evaluate('scrollY') - reading_scroll) <= 2)
    page.goto(HTML.as_uri() + '#slide-3')
    page.wait_for_function('window.NexusDualView?.snapshot().active')
    check('direct hash opens exact slide', page.evaluate('NexusDualView.snapshot().index===2'))
    page.locator('[data-dv-next]').click()
    check('next writes hash', page.evaluate("location.hash==='#slide-4'"))
    page.go_back()
    page.wait_for_function('NexusDualView.snapshot().index===2')
    check('browser back exact slide', page.evaluate("location.hash==='#slide-3'"))
    page.go_forward()
    page.wait_for_function('NexusDualView.snapshot().index===3')
    check('browser forward exact slide', page.evaluate("location.hash==='#slide-4'"))
    page.locator('#presentation-depth').focus()
    page.keyboard.press('End')
    check('native select owns End', page.evaluate('NexusDualView.snapshot().index===3') and page.locator('#presentation-depth').input_value() == 'deep-dive')
    native = page.locator('[data-dv-slide]:not([hidden]) [data-dv-native]')
    native.focus()
    page.keyboard.press('Home')
    check('native source scroll region owns Home', page.evaluate('NexusDualView.snapshot().index===3'))
    link_slide = next((i for i in range(len(model['slides'])) if page.locator('[data-dv-slide]').nth(i).locator('.deep-source a').count()), None)
    if link_slide is not None:
        page.locator('[data-dv-picker]').select_option(str(link_slide))
        link = page.locator('[data-dv-slide]:not([hidden]) .deep-source a').first
        link.focus()
        page.keyboard.press('ArrowRight')
        check('native link owns ArrowRight', page.evaluate('NexusDualView.snapshot().index') == link_slide)
    page.locator('[data-dv-picker]').select_option('3')
    page.locator('[data-dv-next]').focus()
    page.keyboard.press('Space')
    check('native button Space activates exactly once', page.evaluate('NexusDualView.snapshot().index===4'))
    page.locator('[data-dv-picker]').select_option('3')
    for theme in ('dark','light','mixed'):
        page.locator('#presentation-theme').select_option(theme)
        wanted = model['slides'][3]['theme'] if theme == 'mixed' else theme
        check('theme outcome ' + theme, page.locator('[data-dv-deck]').get_attribute('data-theme') == wanted)
    # Frame-sample every source-backed block, plus exact model lede/points. No numbers are invented from DOM.
    for index, slide in enumerate(model['slides']):
        page.locator('[data-dv-picker]').select_option(str(index))
        for depth in ('balanced','deep-dive'):
            page.locator('#presentation-depth').select_option(depth)
            expected = [{'selector':'.slide-lede','offset':0,'text':slide['lede']}]
            expected += [{'selector':'.slide-points li','offset':i,'text':text} for i,text in enumerate(slide['points'])]
            expected += [{'selector':f'.deep-source [data-source-unit="{v["unit"]}"] :is({v["selector"]})', 'offset':v['offset'],'text':v['text']} for v in inventory['source_values'] if v['unit'] in slide['units']]
            page.evaluate("""expected => {
              window.__valueWatch={samples:0,errors:[]}; const state=window.__valueWatch;
              const root=document.querySelector('[data-dv-slide]:not([hidden])');
              const sample=()=>{state.samples++; for(const v of expected){const e=root.querySelectorAll(v.selector)[v.offset];
                if(!e){state.errors.push('missing '+v.selector);continue;} if(!e.getClientRects().length)continue;
                const actual=e.textContent.replace(/\\s+/g,' ').trim(); if(actual!==v.text&&!state.errors.includes(v.selector+': '+actual))state.errors.push(v.selector+': '+actual);}};
              const observer=new MutationObserver(sample);observer.observe(root,{subtree:true,childList:true,characterData:true,attributes:true,attributeFilter:['style','class','hidden']});
              let running=true;const frame=()=>{if(running){sample();requestAnimationFrame(frame)}};sample();requestAnimationFrame(frame);
              window.__stopValueWatch=()=>{running=false;observer.disconnect();return state;};
            }""", expected)
            page.locator('[data-dv-replay]').click()
            sample_motion = "e=>[...e.querySelectorAll('[data-dv-animate]')].filter(n=>n.getClientRects().length).map(n=>{const r=n.getBoundingClientRect();return {opacity:Number(getComputedStyle(n).opacity),left:r.left,right:r.right,top:r.top,bottom:r.bottom}})"
            initial_state = page.locator('[data-dv-slide]:not([hidden])').evaluate(sample_motion)
            page.wait_for_timeout(250)
            middle = page.locator('[data-dv-slide]:not([hidden])').evaluate('(e)=>e.getAnimations({subtree:true}).map(a=>({state:a.playState,time:a.currentTime}))')
            middle_state = page.locator('[data-dv-slide]:not([hidden])').evaluate(sample_motion)
            visible_motion = page.locator('[data-dv-slide]:not([hidden]) [data-dv-animate]').evaluate_all('(es)=>es.filter(e=>e.getClientRects().length).length')
            page.wait_for_timeout(850)
            final_state = page.locator('[data-dv-slide]:not([hidden])').evaluate(sample_motion)
            state = page.evaluate('__stopValueWatch()')
            check(f'frame-sampled source/model values {slide["id"]} {depth}', not state['errors'] and state['samples'] >= 3, state)
            check(f'normal motion applicability {slide["id"]} {depth}', not visible_motion or bool(middle) and all(a['time'] is not None for a in middle), {'visible_authored_targets':visible_motion,'animations':middle})
            check(f'normal motion states {slide["id"]} {depth}', not visible_motion or all(v['opacity'] >= .999 for v in final_state), {'initial':initial_state,'intermediate':middle_state,'final':final_state})
            if depth == 'balanced':
                page.locator('[data-dv-replay]').click()
                restarted = page.locator('[data-dv-slide]:not([hidden])').evaluate('(e)=>e.getAnimations({subtree:true}).map(a=>a.currentTime)')
                check('replay restarts ' + slide['id'], bool(restarted) and all(t is not None and t < 150 for t in restarted), restarted)
        if index < len(model['slides'])-1:
            old_id = slide['id']
            page.locator('[data-dv-next]').click()
            check('hidden slide motion stops ' + old_id, page.locator(f'[data-dv-slide="{old_id}"]').evaluate("e=>e.hidden&&e.inert&&e.getAnimations({subtree:true}).every(a=>a.playState!=='running')"))
    page.locator('[data-dv-exit]').click()
    check('hidden deck motion stops', page.locator('[data-dv-deck]').evaluate("e=>e.hidden&&e.inert&&e.getAnimations({subtree:true}).every(a=>a.playState!=='running')"))
    # Real API request: record actual browser capability, never infer success from fitted mode.
    page.locator('[data-dv-open]').first.click()
    page.wait_for_timeout(150)
    actual_fullscreen = page.evaluate('document.fullscreenElement===document.querySelector("[data-dv-deck]")')
    fullscreen = {'entered': actual_fullscreen, 'fullscreenEnabled': page.evaluate('document.fullscreenEnabled'),
                  'scope':'Actual Chromium document fullscreen API, not host OS window/chrome proof'}
    if actual_fullscreen:
        page.locator('[data-dv-fullscreen]').click()
        page.wait_for_function('!document.fullscreenElement')
        check('actual fullscreen exit keeps fitted presentation', page.evaluate('NexusDualView.snapshot().active'))
        page.locator('[data-dv-fullscreen]').click()
        page.wait_for_function('document.fullscreenElement!==null')
        check('actual fullscreen reentry', page.evaluate('NexusDualView.snapshot().fullscreen'))
    else:
        fullscreen['limitation'] = 'Actual fullscreen entry unavailable in this browser run; no success claim.'
    page.locator('[data-dv-exit]').click()
    page.wait_for_function('!NexusDualView.snapshot().active&&!document.fullscreenElement')
    fullscreen['closed_cleanly'] = True
    context.close()
    forced = browser.new_context(viewport={'width':1366,'height':768}, reduced_motion='reduce', forced_colors='active')
    page = forced.new_page()
    page.set_default_timeout(5000)
    page.on('pageerror', lambda error: errors.append(str(error)))
    page.on('request', lambda request: requests.append(request.url) if request.url.startswith(('http:', 'https:')) else None)
    page.goto(HTML.as_uri())
    page.wait_for_function("document.documentElement.dataset.dvReady === 'true'")
    check('forced colors media active', page.evaluate("matchMedia('(forced-colors: active)').matches"))
    page.evaluate('NexusDualView.open(1)')
    page.locator('#presentation-depth').select_option('deep-dive')
    native = page.locator('[data-dv-slide]:not([hidden]) [data-dv-native]')
    check('forced colors native scrollbar', native.evaluate("e=>getComputedStyle(e).scrollbarColor==='auto'"))
    native.focus()
    page.keyboard.press('End')
    try:
        page.wait_for_function("e=>e.scrollTop+e.clientHeight>=e.scrollHeight-2", arg=native.element_handle(), timeout=3000)
        check('forced colors keyboard source scrolling', True)
    except PlaywrightTimeoutError:
        check('forced colors keyboard source scrolling', False, native.evaluate('e=>({top:e.scrollTop,height:e.clientHeight,extent:e.scrollHeight})'))
    page.locator('[data-dv-next]').focus()
    page.keyboard.press('Tab')
    check('forced colors control focus indicator', page.evaluate("(()=>{const e=document.activeElement,s=getComputedStyle(e);return e.closest('[data-dv-controls]')!==null&&s.outlineStyle!=='none'&&parseFloat(s.outlineWidth)>=2})()"))
    contrast = contrast_findings(page.locator('[data-dv-controls] button:not(:disabled),[data-dv-controls] label'))
    check('forced colors control contrast', not contrast, contrast)
    forced.close()
    return fullscreen


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
    findings, checks, errors, requests, measurements = [], [], [], [], []
    inventory = independent_inventory()

    def check(name, condition, detail=None):
        checks.append({'name': name, 'pass': bool(condition), 'detail': detail})
        if not condition:
            findings.append({'name': name, 'detail': detail})

    record = json.loads(re.search(r'<script[^>]*id="handbook-record"[^>]*>(.*?)</script>', HTML.read_text(encoding='utf-8'), re.S).group(1))
    source_count = len(record['source_units'])
    slide_count = len(record['slides'])
    check('independent source/model unit coverage', inventory['units'] == record['source_units'])
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(headless=True)
        version = browser.version
        for width, height in VIEWPORTS:
            context = browser.new_context(viewport={'width': width, 'height': height}, reduced_motion='reduce')
            # Deliberate API-denial exercise: presentation must remain usable in-window.
            context.add_init_script("Element.prototype.requestFullscreen = () => Promise.reject(new Error('QA fullscreen denied'))")
            page = context.new_page()
            page.on('pageerror', lambda error: errors.append(str(error)))
            page.on('console', lambda message: errors.append(message.text) if message.type == 'error' else None)
            page.on('request', lambda request: requests.append(request.url) if request.url.startswith(('http:', 'https:')) else None)
            page.goto(HTML.as_uri())
            page.wait_for_function("document.documentElement.dataset.dvReady === 'true'")
            page.evaluate('document.fonts.ready')
            prefix = f'{width}x{height}'
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
            check(prefix + ' reading dark control', page.locator('[data-dv-page]').get_attribute('data-reading-theme') == 'dark')
            page.locator('#reading-theme').select_option('light')
            check(prefix + ' reading light control', page.locator('[data-dv-page]').get_attribute('data-reading-theme') == 'light')
            page.locator('#reading-theme').select_option('dark')
            page.locator('#reading-depth').select_option('overview')
            check(prefix + ' reading overview control', not any(page.locator('[data-dv-page] .technical').nth(i).is_visible() for i in range(page.locator('[data-dv-page] .technical').count())))
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
                    geometry = page.locator('[data-dv-slide]:not([hidden])').evaluate(GEOMETRY)
                    measurements.append({'viewport':[width,height],'depth':depth,'slide':inventory['model']['slides'][index]['id'],**geometry})
                    fits = geometry['left'] >= -2 and geometry['right'] <= width + 2 and geometry['controlsBottom'] <= height + 2
                    fits = fits and (geometry['bottom'] <= geometry['controlsTop'] + 2 or geometry['scrollAllowed'])
                    check(f'{prefix} {depth} slide {index + 1} geometry', fits, geometry)
                    check(f'{prefix} {depth} slide {index + 1} descendants', geometry['descendantEscapeCount'] == 0, geometry['descendantEscapes'])
                    check(f'{prefix} {depth} slide {index + 1} stage text floor', not geometry['belowStageFloor'], {'floor':geometry['stageFontFloor'],'minimum':geometry['minimumFont'],'violations':geometry['belowStageFloor']})
                    check(f'{prefix} {depth} slide {index + 1} text-size count', len(geometry['fontSizes']) <= 4, geometry['fontSizes'])
                    if depth == 'deep-dive' and inventory['model']['slides'][index]['id'] == 'pages':
                        labels = page.locator('[data-dv-slide]:not([hidden]) [data-source-unit="benefits-page-kinds"] tr > :first-child').evaluate_all("""es=>es.map(e=>{const r=document.createRange();r.selectNodeContents(e);return {text:e.textContent,lines:new Set([...r.getClientRects()].map(b=>Math.round(b.top))).size}})""")
                        check(prefix + ' short page labels remain whole', all(label['lines'] == 1 for label in labels), labels)
                        reach = page.locator('[data-dv-slide]:not([hidden]) [data-source-unit="benefits-page-kinds"] .table-wrap').evaluate("e=>{const old=e.scrollLeft;e.scrollLeft=e.scrollWidth;const result={needed:e.scrollWidth>e.clientWidth+1,reached:e.scrollLeft>0};e.scrollLeft=old;return result}")
                        check(prefix + ' table horizontal scroll reachable', not reach['needed'] or reach['reached'], reach)
                    contrast = contrast_findings(page.locator('[data-dv-slide]:not([hidden]) > h2,[data-dv-slide]:not([hidden]) > p,[data-dv-slide]:not([hidden]) .slide-points li,[data-dv-controls] button:not(:disabled),[data-dv-controls] label'))
                    check(f'{prefix} {depth} slide {index + 1} contrast', not contrast, contrast)
                    if width == 1440 and depth == 'balanced':
                        page.screenshot(path=str(args.out / f'slide-{index + 1}.png'))
                    if depth == 'balanced' or depth == 'deep-dive' and width in (390,759,761,999,1001,1024,1366,1920,2560) or not fits or geometry['descendantEscapeCount']:
                        page.screenshot(path=str(args.out / f'{width}x{height}-{depth}-slide-{index + 1}.png'))
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
            check(prefix + ' printable full reading', page.locator('[data-dv-page]').is_visible() and page.locator('[data-dv-page] .technical').evaluate_all("nodes => nodes.length > 0 && nodes.every(node => node.getBoundingClientRect().width > 0 && node.getBoundingClientRect().height > 0 && getComputedStyle(node).visibility !== 'hidden')"))
            context.close()
        context = browser.new_context(java_script_enabled=False, viewport={'width': 420, 'height': 900})
        page = context.new_page()
        page.goto(HTML.as_uri())
        check('no JavaScript reading', page.locator('[data-dv-page]').is_visible() and page.locator('[data-dv-page] .source-section').count() == source_count)
        visible_entries = sum(page.locator('[data-dv-open],[data-dv-chapter]').nth(i).is_visible() for i in range(page.locator('[data-dv-open],[data-dv-chapter]').count()))
        check('no JavaScript hides inactive controls', visible_entries == 0, visible_entries)
        context.close()
        try:
            fullscreen = contract_checks(browser, inventory, check, args.out, errors, requests)
        except Exception as error:
            check('contract behavior execution', False, str(error))
            fullscreen = {'entered':False,'limitation':'Contract behavior run interrupted; remaining behaviors are unverified.'}
        browser.close()
    check('console and runtime errors', not errors, errors)
    check('offline external requests', not requests, requests)
    limitations = [] if fullscreen['entered'] else [fullscreen['limitation']]
    report = {'status': 'fail' if findings else 'incomplete' if limitations else 'pass', 'browser': 'Chromium ' + version,
              'output_sha256': hashlib.sha256(HTML.read_bytes()).hexdigest(),
              'checker_sha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
              'checks': checks, 'findings': findings, 'measurements':measurements,
              'independent_inventory':inventory,'fullscreen_observation':fullscreen,
              'limitations':limitations,'auxiliary_helpers':'No installed native-helper or structural-scorer PASS is asserted by this project-native execution.'}
    (args.out / 'render.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
    print(json.dumps({'status': report['status'], 'checks': len(checks), 'finding_count':len(findings),'limitations':limitations,'first_findings':findings[:5]}, indent=2))
    return 1 if findings else 2 if limitations else 0


if __name__ == '__main__':
    raise SystemExit(main())
