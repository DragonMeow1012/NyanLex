#!/usr/bin/env python3
"""Look up licence + download a mod / shader pack from the official Modrinth API (hub-builder helper).

  python hub_modrinth.py info <slug> [<slug> ...]
  python hub_modrinth.py get  <slug> --dest <scratch dir> [--mc 1.21.1] [--loader fabric|neoforge|forge|none]

Rules baked in (see the maintenance guide): User-Agent identifies us, at most 2 requests per second
(sleep 0.6 s between requests), stop on the first HTTP error instead of retrying in a loop, and the
download goes ONLY to the scratch folder you pass in --dest (never into the repository).
`--loader none` is for shader packs (no loader filter).  The newest *release* is preferred; a beta/alpha
is used only when no release matches and is printed with a warning.
"""
import argparse, hashlib, json, os, sys, time, urllib.parse, urllib.request

try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

API = "https://api.modrinth.com/v2"
UA = "DragonMeow/NyanLex-hub-builder (github.com/DragonMeow1012/NyanLex)"
_last = [0.0]


def request(url, binary=False):
    wait = 0.6 - (time.time() - _last[0])
    if wait > 0:
        time.sleep(wait)
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=60) as r:
        _last[0] = time.time()
        data = r.read()
    return data if binary else json.loads(data.decode("utf-8"))


def project(slug):
    return request(f"{API}/project/{urllib.parse.quote(slug)}")


def versions(slug, mc, loader):
    q = {}
    if loader != "none":
        q["loaders"] = json.dumps([loader])
    if mc:
        q["game_versions"] = json.dumps([mc])
    url = f"{API}/project/{urllib.parse.quote(slug)}/version"
    if q:
        url += "?" + urllib.parse.urlencode(q)
    return request(url)


def pick(vs):
    for v in vs:  # the API returns newest first
        if v.get("version_type") == "release":
            return v, True
    return (vs[0], False) if vs else (None, False)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("cmd", choices=["info", "get"])
    ap.add_argument("slugs", nargs="+")
    ap.add_argument("--dest")
    ap.add_argument("--mc", default="1.21.1")
    ap.add_argument("--loader", default="fabric")
    a = ap.parse_args()
    for slug in a.slugs:
        p = project(slug)
        lic = p.get("license") or {}
        print(f"{slug}: title={p.get('title')!r} project_type={p.get('project_type')} "
              f"license.id={lic.get('id')!r} license.name={lic.get('name')!r} license.url={lic.get('url')!r}")
        vs = versions(slug, a.mc, a.loader)
        v, is_release = pick(vs)
        if v is None:
            print(f"  no version for mc={a.mc} loader={a.loader} (try another loader/MC, or skip and note it in the report)")
            continue
        f = next((x for x in v["files"] if x.get("primary")), v["files"][0])
        print(f"  version={v['version_number']} type={v['version_type']}{'' if is_release else '  (NOT a release: warning)'} "
              f"mc={v['game_versions'][:4]} loaders={v['loaders']} file={f['filename']} ({f['size']} bytes)")
        if a.cmd == "get":
            if not a.dest:
                sys.exit("--dest is required for get")
            folder = os.path.join(a.dest, slug)
            os.makedirs(folder, exist_ok=True)
            data = request(f["url"], binary=True)
            want = f["hashes"].get("sha512")
            if want and hashlib.sha512(data).hexdigest() != want:
                sys.exit(f"sha512 mismatch for {f['filename']}")
            path = os.path.join(folder, f["filename"])
            open(path, "wb").write(data)
            print(f"  saved {path} (sha512 verified)")


if __name__ == "__main__":
    main()
