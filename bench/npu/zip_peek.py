#!/usr/bin/env python3
"""Заглянуть в zip по сети, не скачивая его целиком: HTTP Range читает только оглавление (конец архива) и нужные файлы.

Так проверялись лицензии QAIRT SDK (2,6 ГБ) и qnn-runtime: читалось 2–3 МБ. Ничего не исполняется, файлы — только
туда, куда сказано.
  zip_peek.py list URL [РЕГУЛЯРКА]           # имя, размер, сжатый размер, crc
  zip_peek.py get URL ИМЯ_В_АРХИВЕ… --to КАТАЛОГ
"""
import argparse
import io
import re
import sys
import urllib.request
import zipfile
from pathlib import Path


class HttpFile(io.RawIOBase):
    BLOCK = 1 << 20

    def __init__(self, url):
        self.url, self.pos, self.fetched, self.buf0, self.buf = url, 0, 0, 0, b""
        with urllib.request.urlopen(urllib.request.Request(url, headers={"Range": "bytes=0-0"}), timeout=60) as r:
            self.size = int(r.headers["Content-Range"].split("/")[1])

    def readable(self): return True
    def seekable(self): return True
    def tell(self): return self.pos

    def seek(self, off, wh=0):
        self.pos = off if wh == 0 else self.pos + off if wh == 1 else self.size + off
        return self.pos

    def read(self, n=-1):
        if n is None or n < 0:
            n = self.size - self.pos
        n = min(n, self.size - self.pos)
        if n <= 0:
            return b""
        if not (self.buf0 <= self.pos and self.pos + n <= self.buf0 + len(self.buf)):
            end = min(self.size, self.pos + max(n, self.BLOCK))
            req = urllib.request.Request(self.url, headers={"Range": f"bytes={self.pos}-{end - 1}"})
            with urllib.request.urlopen(req, timeout=300) as r:
                self.buf0, self.buf = self.pos, r.read()
            self.fetched += len(self.buf)
        o = self.pos - self.buf0
        d = self.buf[o:o + n]
        self.pos += len(d)
        return d

    def readinto(self, b):
        d = self.read(len(b))
        b[:len(d)] = d
        return len(d)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    l = sub.add_parser("list"); l.add_argument("url"); l.add_argument("pattern", nargs="?", default="")
    g = sub.add_parser("get"); g.add_argument("url"); g.add_argument("names", nargs="+"); g.add_argument("--to", required=True)
    a = ap.parse_args()
    f = HttpFile(a.url)
    z = zipfile.ZipFile(f)
    if a.cmd == "list":
        tot = totc = 0
        for i in z.infolist():
            if a.pattern and not re.search(a.pattern, i.filename):
                continue
            tot += i.file_size; totc += i.compress_size
            print(f"{i.file_size:>11} {i.compress_size:>11} {i.CRC:08x}  {i.filename}")
        print(f"итого {tot / 1e6:.1f} МБ (сжато {totc / 1e6:.1f} МБ); архив {f.size / 1e9:.2f} ГБ, прочитано {f.fetched / 1e6:.1f} МБ",
              file=sys.stderr)
        return 0
    out = Path(a.to); out.mkdir(parents=True, exist_ok=True)
    for n in a.names:
        d = z.read(n)
        (out / Path(n).name).write_bytes(d)
        print(f"{n} → {out / Path(n).name} ({len(d) / 1e6:.1f} МБ)")
    print(f"прочитано {f.fetched / 1e6:.1f} МБ из {f.size / 1e9:.2f} ГБ", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
