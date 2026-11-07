#!/usr/bin/env python3
"""
배포용 jar 만들기 — gradle 빌드 결과를 최대한 가볍게 다시 싼다 (F3.4~).

  python3 -I tools/pack-release.py <jdk-home> <in.jar> <out.jar>
  (필요: pip install zopfli pyoxipng — 없으면 그 단계만 건너뛴다)

하는 일 (통신 규격·동작은 그대로, 바이트만 줄인다)
  1. kwik(QUIC) 줄이기 — 우리 클래스의 상수 풀(javap -v)에서 라이브러리 참조를 뽑아 R8 keep 규칙을
     만들고(손으로 관리하는 목록 없음), R8로 안 닿는 코드 제거 + 최적화 + 내부 이름 줄이기 + 한 패키지로
     모으기. 우리 클래스는 R8에 넣지 않는다 — 믹스인은 바이트코드가 바뀌면 안 되고, 마크 클래스 없이는
     R8이 읽지도 못한다.
  2. LGPL — 줄인 라이브러리는 모드 jar 안의 별도 jar(META-INF/jars/, 패브릭 jar-in-jar)로 싣는다.
     사용자가 원본 kwik으로 갈아 끼울 수 있다. 라이선스 원문(licenses/)은 그 안쪽 jar에 한 벌만 둔다.
     이름 대응표(…-kwik-mapping.txt, 스택트레이스 해석용)는 jar에 넣지 않고 out.jar 옆에 남긴다.
  3. 배포판에 필요 없는 우리 클래스 빼기 — kfc/udp/merged(여러 버전을 한 jar로 묶을 때만 씀),
     QuicProbe(혼자 NAT 통과를 시험하는 하니스).
  4. 우리 클래스의 지역변수 디버그 정보(LocalVariableTable/TypeTable) 빼기 — 줄 번호(LineNumberTable)는
     남겨서 크래시 리포트에 줄 번호는 그대로 찍힌다. 믹스인 클래스는 손대지 않는다.
  5. 언어 파일 JSON 공백 제거, PNG 무손실 최적화(oxipng), 전체를 zopfli로 다시 압축.

원본 kwik ↔ 줄인 kwik 상호 접속은 scratchpad/kwiktest(5% 손실, 스트림 4개 × 4MB 에코)로 확인했다.
"""
import hashlib, io, json, os, re, struct, subprocess, sys, tempfile, urllib.request, zipfile, zlib

LIB_PREFIXES = ("tech/kwik/", "at/favre/", "io/whitfin/", "com/io7m/")
DROP = {"module-info.class", "META-INF/versions/9/module-info.class"}
# 배포판에서 빼는 우리 클래스(소스에는 남는다)
DROP_OURS = ("kfc/udp/merged/", "kfc/udp/client/quic/QuicProbe")
MIXIN_PKG = "kfc/udp/client/mixin/"
NESTED_ID = "instant-p2p-kwik"
NESTED_VERSION = "0.11+r8"
NESTED_PATH = "META-INF/jars/instant-p2p-kwik-0.11-r8.jar"
FIXED_TIME = (1980, 2, 1, 0, 0, 0)
HERE = os.path.dirname(os.path.abspath(__file__))
R8 = os.path.join(HERE, "r8", "r8-8.13.25.jar")
# 저장소에는 R8(17MB)을 넣지 않는다 — 없으면 구글 메이븐에서 받고 해시로 확인한다.
R8_URL = "https://dl.google.com/android/maven2/com/android/tools/r8/8.13.25/r8-8.13.25.jar"
R8_SHA256 = "0d4f2f68c4fcbc9434eff122f41feb6fb1d98efd2faa530cec1abacab1cea03d"

try:
    import zopfli.zlib as _zopfli
except ImportError:
    _zopfli = None
try:
    import oxipng as _oxipng
except ImportError:
    _oxipng = None


def ensure_r8():
    if not os.path.exists(R8):
        os.makedirs(os.path.dirname(R8), exist_ok=True)
        print("R8 내려받는 중:", R8_URL)
        urllib.request.urlretrieve(R8_URL, R8 + ".part")
        os.replace(R8 + ".part", R8)
    with open(R8, "rb") as f:
        if hashlib.sha256(f.read()).hexdigest() != R8_SHA256:
            os.remove(R8)
            sys.exit("R8 해시가 다르다 — 받은 파일을 지웠다. 다시 실행하거나 URL을 확인할 것.")


def is_lib(name):
    return name.startswith(LIB_PREFIXES) and name.endswith(".class")


def refs_from_our_classes(jdk, our_class_paths):
    """우리 클래스가 참조하는 라이브러리 타입(점 표기) 집합."""
    out = subprocess.run([os.path.join(jdk, "bin", "javap"), "-v", "-p", *our_class_paths],
                         capture_output=True, text=True, errors="replace").stdout
    types = set()
    pat = r"((?:tech/kwik|at/favre|io/whitfin|com/io7m)[\w/$]*)"
    for line in out.splitlines():
        if not re.search(r"= (Methodref|InterfaceMethodref|Fieldref|Class|MethodType|NameAndType|Utf8) ", line) \
                and "extends" not in line and "implements" not in line:
            continue
        for m in re.finditer(pat, line):
            types.add(m.group(1).replace("/", "."))
    return types


# ── 클래스 파일에서 지역변수 디버그 정보만 걷어낸다(나머지 바이트는 그대로) ──
_STRIP_CODE_ATTRS = {b"LocalVariableTable", b"LocalVariableTypeTable"}


def strip_local_vars(b):
    u2 = lambda o: struct.unpack_from(">H", b, o)[0]
    u4 = lambda o: struct.unpack_from(">I", b, o)[0]
    n = u2(8)
    utf8 = {}
    o, i = 10, 1
    while i < n:
        t = b[o]
        o += 1
        if t == 1:
            ln = u2(o); utf8[i] = b[o + 2:o + 2 + ln]; o += 2 + ln
        elif t in (3, 4, 9, 10, 11, 12, 17, 18): o += 4
        elif t in (5, 6): o += 8; i += 1
        elif t in (7, 8, 16, 19, 20): o += 2
        elif t == 15: o += 3
        else: raise ValueError("unknown cp tag %d" % t)
        i += 1
    out = bytearray(b[:o])
    out += b[o:o + 6]; o += 6                      # access, this, super
    ic = u2(o); out += b[o:o + 2 + 2 * ic]; o += 2 + 2 * ic

    def copy_attrs(o, in_code):
        cnt = u2(o); o += 2
        parts = []
        for _ in range(cnt):
            name = utf8.get(u2(o)); ln = u4(o + 2)
            body = b[o + 6:o + 6 + ln]
            if in_code and name in _STRIP_CODE_ATTRS:
                pass
            elif not in_code and name == b"Code":
                # max_stack, max_locals, code_length, code, exception_table, attributes
                cl = struct.unpack_from(">I", body, 4)[0]
                p = 8 + cl
                el = struct.unpack_from(">H", body, p)[0]; p += 2 + 8 * el
                sub, _ = copy_attrs_from(body, p)
                newbody = body[:p] + sub
                parts.append(b[o:o + 2] + struct.pack(">I", len(newbody)) + newbody)
            else:
                parts.append(b[o:o + 6 + ln])
            o += 6 + ln
        return struct.pack(">H", len(parts)) + b"".join(parts), o

    def copy_attrs_from(buf, p):
        cnt = struct.unpack_from(">H", buf, p)[0]; p += 2
        parts = []
        for _ in range(cnt):
            name = utf8.get(struct.unpack_from(">H", buf, p)[0])
            ln = struct.unpack_from(">I", buf, p + 2)[0]
            if name not in _STRIP_CODE_ATTRS:
                parts.append(buf[p:p + 6 + ln])
            p += 6 + ln
        return struct.pack(">H", len(parts)) + b"".join(parts), p

    for _ in range(2):                              # fields, methods
        cnt = u2(o); out += b[o:o + 2]; o += 2
        for _ in range(cnt):
            out += b[o:o + 6]; o += 6
            a, o = copy_attrs(o, False)
            out += a
    a, o = copy_attrs(o, False)                     # class attributes
    out += a
    assert o == len(b), "class parse mismatch"
    return bytes(out)


# ── zopfli 로 압축하는 단순 zip 작성기(zip64 없음 — 1MB짜리 jar에는 필요 없다) ──
def _deflate(data):
    best = zlib.compressobj(9, zlib.DEFLATED, -15)
    raw = best.compress(data) + best.flush()
    if _zopfli is not None and len(data) > 64:
        z = _zopfli.compress(data, numiterations=30)[2:-4]  # zlib 껍데기(헤더 2 + adler32 4) 벗기기
        if len(z) < len(raw):
            raw = z
    return raw


def write_zip(path_or_buf, entries):
    """entries: [(name, bytes, force_store)] — 압축이 이득 없으면 STORED."""
    f = open(path_or_buf, "wb") if isinstance(path_or_buf, str) else path_or_buf
    central = []
    dostime = (FIXED_TIME[3] << 11) | (FIXED_TIME[4] << 5) | (FIXED_TIME[5] // 2)
    dosdate = ((FIXED_TIME[0] - 1980) << 9) | (FIXED_TIME[1] << 5) | FIXED_TIME[2]
    for name, data, force_store in entries:
        nb = name.encode("utf-8")
        crc = zlib.crc32(data) & 0xFFFFFFFF
        comp, method = data, 0
        if not force_store and not name.endswith("/"):
            d = _deflate(data)
            if len(d) < len(data):
                comp, method = d, 8
        off = f.tell()
        flags = 0x800  # UTF-8 이름
        f.write(struct.pack("<IHHHHHIIIHH", 0x04034B50, 20, flags, method, dostime, dosdate,
                            crc, len(comp), len(data), len(nb), 0) + nb + comp)
        central.append(struct.pack("<IHHHHHHIIIHHHHHII", 0x02014B50, 20, 20, flags, method, dostime, dosdate,
                                   crc, len(comp), len(data), len(nb), 0, 0, 0, 0, 0, off) + nb)
    cd_off = f.tell()
    cd = b"".join(central)
    f.write(cd)
    f.write(struct.pack("<IHHHHIIH", 0x06054B50, 0, 0, len(central), len(central), len(cd), cd_off, 0))
    if isinstance(path_or_buf, str):
        f.close()


def slim_resource(name, data):
    if name.endswith(".json") and "/lang/" in name:
        return json.dumps(json.loads(data.decode("utf-8")), ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    if name.endswith(".png") and _oxipng is not None:
        try:
            out = _oxipng.optimize_from_memory(data, level=6)
            return out if len(out) < len(data) else data
        except Exception:
            return data
    return data


def main():
    jdk, src, dst = sys.argv[1:4]
    ensure_r8()
    if _zopfli is None:
        print("참고: zopfli 가 없어 일반 deflate(9)로 압축한다 — pip install zopfli")
    if _oxipng is None:
        print("참고: pyoxipng 가 없어 PNG 최적화를 건너뛴다 — pip install pyoxipng")
    with tempfile.TemporaryDirectory() as tmp:
        zin = zipfile.ZipFile(src)
        ours_dir = os.path.join(tmp, "ours")
        lib_in = os.path.join(tmp, "lib-in.jar")
        our_classes = []
        with zipfile.ZipFile(lib_in, "w", zipfile.ZIP_DEFLATED) as zl:
            for info in zin.infolist():
                n = info.filename
                if is_lib(n):
                    zl.writestr(n, zin.read(info))
                elif n.endswith(".class") and n.startswith("kfc/") and not n.startswith(DROP_OURS):
                    p = os.path.join(ours_dir, n)
                    os.makedirs(os.path.dirname(p), exist_ok=True)
                    with open(p, "wb") as f:
                        f.write(zin.read(info))
                    our_classes.append(p)

        types = refs_from_our_classes(jdk, our_classes)
        if not types:
            sys.exit("우리 코드에서 kwik 참조를 못 찾았다 — javap 출력 형식이 바뀌었나?")
        rules = [
            # 라이브러리 내부라 실행에 필요한 속성만 남긴다(중첩 클래스 정보). 줄 번호·시그니처·지역변수 표는
            # 버린다 — kwik 쪽 스택트레이스는 이름만 mapping 으로 되돌릴 수 있고 줄 번호는 안 나온다.
            "-keepattributes InnerClasses,EnclosingMethod",
            "-optimizationpasses 5",
            "-dontwarn **",
            # 안 쓰는 내부 클래스는 한 패키지로 모아 이름을 짧게(상수 풀이 줄어든다). 접근 제한은 R8이 맞춘다.
            "-repackageclasses 'kwik_r8'",
            "-allowaccessmodification",
            # QuicClient.pingNow 가 리플렉션으로 부른다(구현 클래스에만 공개된 메서드).
            "-keep class tech.kwik.core.impl.QuicClientConnectionImpl { public void ping(); }",
            "-keepclassmembers enum ** { public static **[] values(); public static ** valueOf(java.lang.String); }",
        ] + ["-keep class %s { *; }" % t for t in sorted(types)]
        rules_path = os.path.join(tmp, "rules.pro")
        with open(rules_path, "w") as f:
            f.write("\n".join(rules) + "\n")

        lib_out = os.path.join(tmp, "lib-out.jar")
        mapping = os.path.splitext(dst)[0] + "-kwik-mapping.txt"
        r = subprocess.run([os.path.join(jdk, "bin", "java"), "-cp", R8, "com.android.tools.r8.R8",
                            "--release", "--classfile", "--lib", jdk, "--pg-conf", rules_path,
                            "--pg-map-output", mapping, "--output", lib_out, lib_in],
                           capture_output=True, text=True)
        if r.returncode != 0 or not os.path.exists(lib_out):
            sys.exit("R8 실패:\n" + r.stdout + r.stderr)
        zlo = zipfile.ZipFile(lib_out)

        # ── 안쪽 jar(줄인 kwik + 라이선스 원문) ──
        nested_entries = [("fabric.mod.json", json.dumps({
            "schemaVersion": 1,
            "id": NESTED_ID,
            "version": NESTED_VERSION,
            "name": "kwik QUIC (LGPL-3.0, instant-p2p 번들)",
            "description": "kwik 0.11 + agent15 3.3 + hkdf 2.0.0 + siphash 2.0.1 — instant-p2p가 쓰는 만큼만 R8로 줄인 것. "
                           "원본: https://github.com/ptrd/kwik . 교체 방법은 THIRD_PARTY_NOTICES.md.",
            "license": ["LGPL-3.0-only", "Apache-2.0", "MIT"],
            "environment": "*",
            "custom": {"fabric-loom:generated": True},
        }, ensure_ascii=False, separators=(",", ":")).encode("utf-8"), False)]
        for info in zlo.infolist():
            if info.filename.endswith(".class"):
                nested_entries.append((info.filename, zlo.read(info), False))
        for info in zin.infolist():
            n = info.filename
            if (n.startswith("licenses/") or n == "THIRD_PARTY_NOTICES.md" or n.startswith("META-INF/maven/")) \
                    and not n.endswith("/"):
                nested_entries.append((n, zin.read(info), False))
        nested = io.BytesIO()
        write_zip(nested, nested_entries)
        nested_bytes = nested.getvalue()

        # ── 바깥 jar ──
        entries, stripped = [], 0
        for info in zin.infolist():
            n = info.filename
            if (is_lib(n) or n in DROP or n.startswith("META-INF/maven/") or n.startswith("licenses/")
                    or n.endswith("/") or n.startswith(DROP_OURS)):
                continue  # 라이선스 원문은 안쪽 jar에 한 벌만(바깥엔 MIT LICENSE·고지만)
            data = zin.read(info)
            if n == "fabric.mod.json":
                meta = json.loads(data.decode("utf-8"))
                meta["jars"] = [j for j in meta.get("jars", []) if j.get("file") != NESTED_PATH] + [{"file": NESTED_PATH}]
                meta.setdefault("depends", {})[NESTED_ID] = "*"
                data = json.dumps(meta, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
            elif n.endswith(".class") and n.startswith("kfc/") and not n.startswith(MIXIN_PKG):
                before = len(data)
                data = strip_local_vars(data)
                stripped += before - len(data)
            else:
                data = slim_resource(n, data)
            entries.append((n, data, False))
        entries.append((NESTED_PATH, nested_bytes, True))  # 안쪽 jar는 압축 없이 — 로더가 그대로 연다
        write_zip(dst, entries)

        before = sum(i.compress_size for i in zin.infolist() if is_lib(i.filename))
        print(f"kwik {before // 1024}KB -> {len(nested_bytes) // 1024}KB(안쪽 jar), 지역변수 정보 -{stripped // 1024}KB, "
              f"jar {os.path.getsize(src) // 1024}KB -> {os.path.getsize(dst) // 1024}KB (keep roots {len(types)})")


if __name__ == "__main__":
    main()
