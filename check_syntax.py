import glob, sys

def strip_strings(src):
    out, i, n = [], 0, len(src)
    while i < n:
        c = src[i]
        if c == "'" or c == '"':
            j = i + 1
            while j < n:
                if src[j] == '\\':
                    j += 2
                    continue
                if src[j] == c:
                    break
                j += 1
            i = j + 1
        elif c == '/' and i + 1 < n and src[i+1] == '/':
            j = src.find('\n', i)
            i = j if j != -1 else n
        elif c == '/' and i + 1 < n and src[i+1] == '*':
            j = src.find('*/', i)
            i = j + 2 if j != -1 else n
        else:
            out.append(c)
            i += 1
    return ''.join(out)

ok = True
for f in sorted(glob.glob('lib/**/*.dart', recursive=True)):
    src = open(f, encoding='utf-8').read()
    s = strip_strings(src)
    for a, b in [('(', ')'), ('[', ']'), ('{', '}')]:
        if s.count(a) != s.count(b):
            print(f'FAIL {f}: {a}{s.count(a)} vs {b}{s.count(b)}')
            ok = False
print('ALL OK' if ok else 'HAS ERRORS')
sys.exit(0 if ok else 1)
