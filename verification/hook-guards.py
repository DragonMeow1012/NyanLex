#!/usr/bin/env python3
"""Apply / verify the HookGuard + HookHealth wrapper on every glue entry point of every loader tree.

  python verification/hook-guards.py apply   # idempotently wrap entry points, regenerate NyanLexHooks.java
  python verification/hook-guards.py check   # exit 1 if any entry point is unwrapped or a hook list drifted

Entry points handled:
  * mixin handlers: methods annotated @Inject / @Redirect / @ModifyVariable / @ModifyArg in *Mixin.java
  * event handlers: methods annotated @SubscribeEvent (NeoForge / Forge)
  * named bytecode-patched hooks (Forge 1.12.2 / 1.13.2 ScreenText transformer -> translateScreenString)
  * Fabric event lambdas / callbacks (EXACT_RULES below: ALLOW_GAME, ALLOW_CHAT, tooltip, tick, screen events)

A wrapped method looks like

    if (!HookGuard.enter("Mixin.handler")) return <fallback>;
    try { <original body> } catch (Throwable guardError) { HookGuard.fail("Mixin.handler", guardError); return <fallback>; }

with <fallback> = the original, untranslated value (the modified argument, or the redirected call
made the way vanilla would). Begin/end style pairs use enterSticky (never auto-disabled).
Every tree gets a generated NyanLexHooks.register(...) listing every hook id found in its glue
sources; the loader entry point calls it so "registered but never hit" hooks can be reported.
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TREES = ['src', 'fabric1144', 'fabric1152', 'fabric1165', 'fabric1171', 'fabric1182', 'fabric1194',
         'fabric120', 'fabric12111', 'fabric26', 'fabric2612', 'neoforge', 'neoforge120', 'neoforge26',
         'forge1122', 'forge1132']
CORE_PKGS = {'cache', 'config', 'hub', 'service', 'style', 'translate'}
ANNOTS = ('Inject', 'Redirect', 'ModifyVariable', 'ModifyArg', 'SubscribeEvent')
STICKY_RE = re.compile(r'^(enter|exit|begin|end|before|after|prepare|clear)')
AMBIENT_RE = re.compile(r'(^DebugHud\.)|(^ChatComponent\.(enter|exit))|(^Hud\..*[Dd]ebug)'
                        r'|(^event\.(onOverlayText|onOverlayPost)$)')
GUARD_USE = re.compile(r'HookGuard\.(?:enter|enterSticky|run|runSticky|call)\(\s*"([^"]+)"')
IMPORT = 'import com.dragonmeow.nyanlex.translate.HookGuard;'


# ---------------------------------------------------------------- tiny Java scanner
def skip_string(s, i):
    q = s[i]
    i += 1
    while s[i] != q:
        i += 2 if s[i] == '\\' else 1
    return i + 1


def match_close(s, i, open_c, close_c):
    """s[i] == open_c; return the index just past the matching close, honouring strings/comments."""
    depth = 0
    n = len(s)
    while i < n:
        c = s[i]
        if c in '"\'':
            i = skip_string(s, i)
            continue
        if s.startswith('//', i):
            i = s.index('\n', i)
            continue
        if s.startswith('/*', i):
            i = s.index('*/', i) + 2
            continue
        if c == open_c:
            depth += 1
        elif c == close_c:
            depth -= 1
            if depth == 0:
                return i + 1
        i += 1
    raise ValueError('unbalanced')


def split_params(p):
    out, depth, cur = [], 0, ''
    for c in p:
        if c == '<':
            depth += 1
        elif c == '>':
            depth -= 1
        if c == ',' and depth == 0:
            out.append(cur.strip())
            cur = ''
        else:
            cur += c
    if cur.strip():
        out.append(cur.strip())
    return out


def desc_arg_count(desc):
    inner = desc[desc.index('(') + 1:desc.index(')')]
    n, i = 0, 0
    while i < len(inner):
        while inner[i] == '[':
            i += 1
        if inner[i] == 'L':
            i = inner.index(';', i)
        i += 1
        n += 1
    return n


SIG_RE = re.compile(r'\s*((?:private|protected|public|static|final|abstract|synchronized)\s+)*'
                    r'(?P<ret>[\w.<>\[\], ?]+?)\s+(?P<name>\w[\w$]*)\s*\(')


def parse_method_at(src, j, kind, annotation, ann_start):
    sig = SIG_RE.match(src, j)
    if not sig:
        raise ValueError('cannot parse signature after %r' % src[ann_start:ann_start + 80])
    pstart = sig.end() - 1
    pend = match_close(src, pstart, '(', ')')
    params = split_params(src[pstart + 1:pend - 1])
    k = pend
    while src[k] in ' \t\r\n':
        k += 1
    if not src.startswith('{', k):
        return None  # abstract / no body
    body_end = match_close(src, k, '{', '}')
    return dict(kind=kind, annotation=annotation, ret=sig.group('ret').strip(), name=sig.group('name'),
                params=params, brace=k, end=body_end)


def find_handlers(src, named_hooks=()):
    res = []
    for m in re.finditer(r'^[ \t]*@(%s)\b' % '|'.join(ANNOTS), src, re.M):
        kind = m.group(1)
        if src[m.end():m.end() + 1] == '(':
            ann_end = match_close(src, m.end(), '(', ')')
            annotation = src[m.end():ann_end]
        else:
            ann_end, annotation = m.end(), ''
        j = ann_end
        while True:  # skip further annotations
            mm = re.compile(r'\s*@[\w.]+(\s*\()?').match(src, j)
            if not mm:
                break
            j = match_close(src, mm.end() - 1, '(', ')') if mm.group(1) else mm.end()
        h = parse_method_at(src, j, kind, annotation, m.start())
        if h:
            res.append(h)
    for name in named_hooks:
        for m in re.finditer(r'^[ \t]*(?:public |private |protected )?static [\w<>\[\]]+ %s\(' % re.escape(name),
                             src, re.M):
            h = parse_method_at(src, m.start(), 'Hook', '', m.start())
            if h:
                res.append(h)
    res.sort(key=lambda h: h['brace'])
    return res


def param_name(p):
    return re.search(r'(\w[\w$]*)\s*$', p).group(1)


def fallback_for(h):
    names = [param_name(p) for p in h['params']]
    if h['ret'] == 'void':
        return None
    if h['kind'] in ('ModifyVariable', 'ModifyArg', 'Hook'):
        return names[0]
    if h['kind'] == 'Redirect':
        tpos = h['annotation'].find('target')
        if tpos < 0:
            raise ValueError('redirect %s has no target' % h['name'])
        target = ''.join(re.findall(r'"((?:[^"\\]|\\.)*)"', h['annotation'][tpos:]))
        m = re.search(r';(\w+)(\([^)]*\)\S+)', target)
        if not m:
            raise ValueError('redirect target not parseable: %r' % target)
        meth, desc = m.group(1), m.group(2)
        argc = desc_arg_count(desc)
        if len(names) == argc + 1:
            return '%s.%s(%s)' % (names[0], meth, ', '.join(names[1:]))
        raise ValueError('redirect %s: %d params for descriptor with %d args (static/captured?)'
                         % (h['name'], len(names), argc))
    raise ValueError('no fallback rule for %s %s' % (h['kind'], h['name']))


# ---------------------------------------------------------------- ids
def hook_id(path, h):
    name = h['name'].replace('nyanlex$', '', 1)
    if h['kind'] == 'SubscribeEvent':
        return 'event.' + name
    if h['kind'] == 'Hook':
        return 'hook.' + name
    base = os.path.basename(path)[:-len('.java')]
    if base.endswith('Mixin'):
        base = base[:-5]
    return '%s.%s' % (base, name)


def is_sticky(h, names):
    n = h['name'].replace('nyanlex$', '', 1)
    if STICKY_RE.match(n):
        return True
    # Pre/Post event pairs share render state (push/pop): never half-disable them.
    m = re.match(r'^(.*?)(Pre|Post)$', n)
    if m and h['kind'] == 'SubscribeEvent':
        other = m.group(1) + ('Post' if m.group(2) == 'Pre' else 'Pre')
        return other in names
    return False


NAMED_HOOKS = {'NyanLexForge.java': ('translateScreenString',)}


def transform_handlers(path, src):
    handlers = find_handlers(src, NAMED_HOOKS.get(os.path.basename(path), ()))
    names = {h['name'].replace('nyanlex$', '', 1) for h in handlers}
    out, pos = [], 0
    for h in handlers:
        body = src[h['brace']:h['end']]
        hid = hook_id(path, h)
        if 'HookGuard.' in body:
            continue
        if h['kind'] == 'SubscribeEvent' and h['ret'] != 'void':
            raise ValueError('non-void @SubscribeEvent %s in %s' % (h['name'], path))
        sticky = is_sticky(h, names)
        fb = fallback_for(h)
        ret = 'return;' if fb is None else 'return %s;' % fb
        inner = body[1:-1].strip('\n').rstrip()
        inner = '\n'.join(('    ' + l if l.strip() else l) for l in inner.split('\n'))
        enter = ('HookGuard.enterSticky("%s");' % hid) if sticky else \
                ('if (!HookGuard.enter("%s")) %s' % (hid, ret))
        catch_ret = '' if fb is None else '\n            ' + ret
        new = ('{\n        %s\n        try {\n%s\n        } catch (Throwable guardError) {\n'
               '            HookGuard.fail("%s", guardError);%s\n        }\n    }') % (enter, inner, hid, catch_ret)
        out.append(src[pos:h['brace']])
        out.append(new)
        pos = h['end']
    out.append(src[pos:])
    return ''.join(out)


# ---------------------------------------------------------------- exact-text rules (Fabric lambdas etc.)
# (needle regex, replacement, human label). Applied once per file; a rule that matches 0 times is
# simply not applicable to that tree. Idempotent: replaced text no longer matches the needle.
def _w(s):
    return s


EXACT_RULES = [
    ('ALLOW_GAME',
     re.compile(r'ClientReceiveMessageEvents\.ALLOW_GAME\.register\(\(message, overlay\) -> \{\s*'
                r'if \(overlay\) return handleOverlayMessage\(message\);\s*'
                r'return !translateAndInject\(message, null\);\s*\}\);'),
     'ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> HookGuard.call("event.allowGame",\n'
     '                () -> overlay ? handleOverlayMessage(message) : !translateAndInject(message, null),\n'
     '                () -> true));'),
    ('ALLOW_CHAT',
     re.compile(r'ClientReceiveMessageEvents\.ALLOW_CHAT\.register\(\(message, signedMessage, sender, params, '
                r'receptionTimestamp\) ->\s*!translateAndInject\(message, params\)\);'),
     'ClientReceiveMessageEvents.ALLOW_CHAT.register((message, signedMessage, sender, params, receptionTimestamp) ->\n'
     '                HookGuard.call("event.allowChat", () -> !translateAndInject(message, params), () -> true));'),
    ('tooltip',
     re.compile(r'\((stack, (?:context, )?type, lines)\) -> onItemTooltip\(stack, lines\)\);'),
     r'(\1) -> HookGuard.run("event.itemTooltip", () -> onItemTooltip(stack, lines)));'),
    ('clientTick',
     re.compile(r'ClientTickEvents\.END_CLIENT_TICK\.register\(this::onClientTick\);'),
     'ClientTickEvents.END_CLIENT_TICK.register(\n'
     '                tickClient -> HookGuard.run("event.clientTick", () -> onClientTick(tickClient)));'),
    ('screenKey3',
     re.compile(r'\(scr, key, scancode, mods\) -> onScreenKey\(scr, key, scancode\)\);'),
     '(scr, key, scancode, mods) ->\n'
     '                            HookGuard.run("event.screenKey", () -> onScreenKey(scr, key, scancode)));'),
    ('screenKeyRef',
     re.compile(r'ScreenKeyboardEvents\.afterKeyPress\(screen\)\.register\(this::onScreenKey\);'),
     'ScreenKeyboardEvents.afterKeyPress(screen).register(\n'
     '                    (scr, keyEvent) -> HookGuard.run("event.screenKey", () -> onScreenKey(scr, keyEvent)));'),
    ('screenKey26',
     re.compile(r'\(scr, keyEvent\) -> onScreenKey\(scr, keyEvent\)\)\);'),
     '(scr, keyEvent) ->\n'
     '                        HookGuard.run("event.screenKey", () -> onScreenKey(scr, keyEvent))));'),
    ('beforeRenderExpr',
     re.compile(r'(ScreenEvents\.beforeRender\(screen\)\.register\(\(scr, graphics, mouseX, mouseY, delta\) ->)\s*'
                r'SCREEN_RENDER_STACK\.get\(\)\.push\(scr\)\);'),
     r'\1 HookGuard.runSticky("event.screenBeforeRender",\n'
     r'                    () -> SCREEN_RENDER_STACK.get().push(scr)));'),
    ('beforeRenderBlock',
     re.compile(r'(ScreenEvents\.beforeRender\(screen\)\.register\(\(scr, graphics, mouseX, mouseY, delta\) ->) \{\s*'
                r'SCREEN_RENDER_STACK\.get\(\)\.push\(scr\);\s*\}\);'),
     r'\1 HookGuard.runSticky("event.screenBeforeRender",\n'
     r'                    () -> SCREEN_RENDER_STACK.get().push(scr)));'),
    ('legacyTooltip',
     re.compile(r'\(stack, context, lines\) -> translateTooltip\(stack, lines\)\);'),
     '(stack, context, lines) ->\n'
     '                HookGuard.run("event.itemTooltip", () -> translateTooltip(stack, lines)));'),
    ('afterRender',
     re.compile(r'(ScreenEvents\.afterRender\(screen\)\.register\(\(scr, graphics, mouseX, mouseY, delta\) ->) \{'
                r'(\s*finishScreenCapture\(scr\);.*?if \(stack\.isEmpty\(\)\) SCREEN_RENDER_STACK\.remove\(\);)'
                r'\s*\}\);', re.S),
     r'\1 HookGuard.runSticky("event.screenAfterRender", () -> {\2\n            }));'),
]

# Loader entry points: insert the hook-list registration as the first statement.
INIT_RULES = [
    (re.compile(r'(@Override public void onInitializeClient\(\) \{\n)'), 'LEGACY'),  # legacy: no slf4j
    (re.compile(r'(public void onInitializeClient\(\) \{\n)'), 'LOGGER'),
    (re.compile(r'(public NyanLexNeoForge(?:26)?\((?:IEventBus modBus, ModContainer container)?\) \{\n)'), 'LOGGER'),
    (re.compile(r'(@Mod\.EventHandler public void init\(FMLInitializationEvent event\) \{\n)'), 'LEGACY'),
    (re.compile(r'(public NyanLexForge\(\) \{\n)'), 'LEGACY'),
]
TOKEN_RE = re.compile(r'(\+ " \| req " \+ tokens\.requests\(\))(?! \+ " \| " \+ com\.dragonmeow)')
TOKEN_NEW = r'\1 + " | " + com.dragonmeow.nyanlex.translate.HookHealth.shortSummary()'


def wrap_block_lambda(src, head, hid):
    """`<head> ... });` where head ends with `-> {`  ->  `<prefix>HookGuard.run("id", () -> { ... }));`"""
    idx = src.find(head)
    if idx < 0:
        return src
    brace = idx + len(head) - 1
    end = match_close(src, brace, '{', '}')
    if not src.startswith(');', end):
        raise ValueError('unexpected tail after lambda block of %s' % head)
    prefix = head[:-len('{')].rstrip()  # "...register(client ->"
    return (src[:idx] + prefix + ' HookGuard.run("%s", () -> ' % hid + src[brace:end] + '));' + src[end + 2:])


def apply_exact(path, src):
    if 'ClientTickEvents.END_CLIENT_TICK.register(client -> {' in src:
        src = wrap_block_lambda(src, 'ClientTickEvents.END_CLIENT_TICK.register(client -> {', 'event.clientTick')
    for _label, needle, repl in EXACT_RULES:
        src = needle.sub(repl, src)
    for needle, mode in INIT_RULES:
        if 'NyanLexHooks.register' in src:
            break
        call = ('NyanLexHooks.register(LOGGER::info, LOGGER::warn);' if mode == 'LOGGER'
                else 'NyanLexHooks.register(null, null);')
        src, n = needle.subn(lambda m: m.group(1) + '        ' + call + '\n', src, count=1)
    src = TOKEN_RE.sub(TOKEN_NEW, src)
    return src


def ensure_import(src):
    if 'HookGuard.' in src and IMPORT not in src:
        # Same-package classes of translate never need it; everything else does.
        return re.sub(r'^(package [^;]+;\s*\n)', r'\1' + IMPORT + '\n', src, count=1)
    return src


# ---------------------------------------------------------------- tree walking
def tree_root(tree):
    # the canonical root tree keeps its sources directly under <repo>/src
    return os.path.join(ROOT, 'src') if tree == 'src' else os.path.join(ROOT, tree, 'src')


def glue_files(tree):
    """All glue .java files of a tree (everything outside the mirrored core packages)."""
    base = os.path.join(tree_root(tree), 'main', 'java', 'com', 'dragonmeow', 'nyanlex')
    if not os.path.isdir(base):
        return
    for dirpath, _dirs, files in os.walk(base):
        rel = os.path.relpath(dirpath, base).split(os.sep)
        if rel[0] in CORE_PKGS or 'build' in rel:
            continue
        for fn in sorted(files):
            if fn.endswith('.java') and fn != 'NyanLexHooks.java':
                yield os.path.join(dirpath, fn)


def hooks_package_dir(tree, files):
    for f in files:
        if os.path.basename(os.path.dirname(f)) == 'mixin':
            return os.path.dirname(os.path.dirname(f))
    for f in files:
        if os.path.basename(f) == 'NyanLexForge.java':
            return os.path.dirname(f)
    return None


def hooks_class(pkg, ids, header):
    ids = sorted(set(ids))
    amb = [i for i in ids if AMBIENT_RE.search(i)]
    ctx = [i for i in ids if not AMBIENT_RE.search(i)]
    fmt = lambda xs: ',\n            '.join('"%s"' % x for x in xs)
    parts = ['package %s;\n\nimport com.dragonmeow.nyanlex.translate.HookHealth;\n\n' % pkg,
             '/** %s (generated by verification/hook-guards.py - do not edit by hand). */\n' % header,
             'public final class NyanLexHooks {\n    private NyanLexHooks() {\n    }\n\n',
             '    /** Registers every guarded hook id; call once from the loader entry point.\n'
             '     *  Sinks may be null (falls back to java.util.logging). */\n',
             '    public static void register(java.util.function.Consumer<String> info,\n'
             '            java.util.function.Consumer<String> warn) {\n        HookHealth.setSinks(info, warn);\n']
    if amb:
        parts.append('        HookHealth.expectAmbient(\n            %s);\n' % fmt(amb))
    if ctx:
        parts.append('        HookHealth.expect(\n            %s);\n' % fmt(ctx))
    parts.append('    }\n}\n')
    return ''.join(parts)


def read(path):
    with open(path, encoding='utf-8', newline='') as f:
        return f.read()


def write(path, text, crlf):
    with open(path, 'w', encoding='utf-8', newline='') as f:
        f.write(text.replace('\n', '\r\n') if crlf else text)


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else 'check'
    bad = 0
    for tree in TREES:
        files = list(glue_files(tree))
        if not files:
            continue
        ids = []
        for path in files:
            src = read(path)
            crlf = '\r\n' in src
            norm = src.replace('\r\n', '\n')
            new = transform_handlers(path, norm)
            new = apply_exact(path, new)
            new = ensure_import(new)
            if new != norm:
                if mode == 'apply':
                    write(path, new, crlf)
                    print('updated   %s' % os.path.relpath(path, ROOT))
                else:
                    print('UNWRAPPED %s' % os.path.relpath(path, ROOT))
                    bad += 1
            ids.extend(GUARD_USE.findall(new))
            # every annotated handler must carry its own enter + fail (belt and braces for `check`)
            for h in find_handlers(new, NAMED_HOOKS.get(os.path.basename(path), ())):
                hid = hook_id(path, h)
                # A handler may name its hook itself (HookGuard.run("<own id>", ...), or an
                # enter/fail pair that shares an id with a sibling handler). Only a handler with no
                # guard call at all, or an enter without its fail, is a real gap.
                body = new[h['brace']:h['end']]
                if 'HookGuard.' in body and (not re.search(r'HookGuard\.enter(Sticky)?\(', body)
                                             or 'HookGuard.fail(' in body):
                    continue
                if new.count('"%s"' % hid) < 2 and mode == 'check':
                    print('MISSING guard for %s in %s' % (hid, path))
                    bad += 1
        pkg_dir = hooks_package_dir(tree, files)
        if pkg_dir is None:
            print('NO HOOK PACKAGE for %s' % tree)
            bad += 1
            continue
        pkg = os.path.relpath(pkg_dir, os.path.join(tree_root(tree), 'main', 'java')).replace(os.sep, '.')
        content = hooks_class(pkg, ids, '%s: %d guarded hooks' % (tree, len(set(ids))))
        target = os.path.join(pkg_dir, 'NyanLexHooks.java')
        existing = read(target) if os.path.exists(target) else None
        if existing != content:
            if mode == 'apply':
                write(target, content, False)
                print('generated %s' % os.path.relpath(target, ROOT))
            else:
                print('HOOK LIST DRIFT %s' % os.path.relpath(target, ROOT))
                bad += 1
        entry_ok = any('NyanLexHooks.register' in read(p) for p in files)
        if not entry_ok:
            print('NO NyanLexHooks.register CALL in %s' % tree)
            bad += 1
        print('%-12s %3d hooks (%d ambient)' % (tree, len(set(ids)), sum(1 for i in set(ids) if AMBIENT_RE.search(i))))
    if mode == 'check' and bad:
        sys.exit(1)
    print('OK' if not bad else 'problems: %d' % bad)


if __name__ == '__main__':
    main()
