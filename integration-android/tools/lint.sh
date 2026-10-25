#!/usr/bin/env bash
# Maven-free sanity check for integration-android (no Android/Flutter SDK required).
#
#  1. Every .kt and .dart file has balanced { } ( ) [ ] outside strings/comments.
#  2. Every io.mosip.vivid.api.* / io.mosip.vivid.device.* class imported by Kotlin exists in the
#     api sources, and every `Class.member` static reference / `Class.Nested` nested type and every
#     `.method(` called on an API builder exists in that class's source.
#  3. The three ARB files are valid JSON with identical key sets and the Dart lookup table covers
#     every key.
#  4. Kotlin files reference only generated Pigeon names that the Pigeon definition declares.
set -u
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
MOD="$ROOT/integration-android"
API="$ROOT/liveness-api/src/main/java/io/mosip/vivid/api"
DEV="$ROOT/device-api/src/main/java/io/mosip/vivid/device"
export ROOT MOD API DEV
python3 -I - <<'PY'
import os, re, sys, json
ROOT=os.environ["ROOT"]; MOD=os.environ["MOD"]; API=os.environ["API"]; DEV=os.environ["DEV"]
errors=[]; warnings=[]

def strip(src, lang):
    """Remove comments and string literals so brace counting is meaningful."""
    out=[]; i=0; n=len(src)
    while i<n:
        c=src[i]
        if src.startswith("//",i):
            j=src.find("\n",i); i=n if j<0 else j; continue
        if src.startswith("/*",i):
            j=src.find("*/",i+2); i=n if j<0 else j+2; continue
        if lang=="kt" and src.startswith('"""',i):
            j=src.find('"""',i+3); i=n if j<0 else j+3; out.append('""'); continue
        if lang=="dart" and (src.startswith("'''",i) or src.startswith('"""',i)):
            q=src[i:i+3]; j=src.find(q,i+3); i=n if j<0 else j+3; out.append('""'); continue
        if c in "\"'":
            q=c; j=i+1
            while j<n and src[j]!=q:
                if src[j]=="\\": j+=1
                elif src[j]=="\n": break
                j+=1
            i=j+1; out.append('""'); continue
        out.append(c); i+=1
    return "".join(out)

def check_balance(path, lang):
    s=strip(open(path,encoding="utf-8").read(), lang)
    pairs={"{":"}","(":")","[":"]"}; stack=[]
    line=1
    for ch in s:
        if ch=="\n": line+=1
        if ch in pairs: stack.append((ch,line))
        elif ch in pairs.values():
            if not stack or pairs[stack[-1][0]]!=ch:
                errors.append(f"{path}:{line}: unbalanced '{ch}'"); return
            stack.pop()
    if stack:
        errors.append(f"{path}:{stack[-1][1]}: unclosed '{stack[-1][0]}'")

kt=[]; dart=[]
for base,dirs,files in os.walk(MOD):
    # Skip build output, Pigeon-generated code and tests (they are compiled/analyzed for real; see
    # docs/testing/android-compile.md) - this script only sanity-checks hand-written sources.
    dirs[:] = [d for d in dirs if d not in ("build", ".gradle", "gen", "pigeon", "test")]
    for f in files:
        p=os.path.join(base,f)
        if f.endswith(".kt"): kt.append(p)
        elif f.endswith(".dart"): dart.append(p)
for p in kt: check_balance(p,"kt")
for p in dart: check_balance(p,"dart")

# ---- API symbol existence ------------------------------------------------------------------
api_src={}
for d,pkg in ((API,"io.mosip.vivid.api"),(DEV,"io.mosip.vivid.device")):
    for f in os.listdir(d):
        if f.endswith(".java"): api_src[(pkg,f[:-5])]=open(os.path.join(d,f),encoding="utf-8").read()

def members(src):
    src=re.sub(r"@\w+(?:\([^)]*\))?\s*", "", src)  # drop annotations such as @Override
    names=set(re.findall(r"\b(?:public|static|final|default)\s+[\w<>\[\], ?]+?\s+(\w+)\s*\(", src))
    # interface / abstract declarations have no modifier: "    SessionState state();"
    names|=set(re.findall(r"^\s*(?:[\w<>\[\]?]+(?:,\s*[\w<>\[\]?]+)*\s+)+(\w+)\s*\([^)]*\)\s*(?:;|\{|throws)", src, re.M))
    if re.search(r"\benum\s+\w+", src): names|={"valueOf","values","name","ordinal"}
    names|=set(re.findall(r"\b(?:enum|class|interface)\s+(\w+)", src))
    names|=set(re.findall(r"public\s+(?:static\s+)?(?:final\s+)?[\w<>\[\]]+\s+(\w+)\s*[;=]", src))
    # enum constants: lines of UPPER_CASE identifiers before ';' inside enum bodies
    for m in re.finditer(r"enum\s+\w+\s*\{([^;]*?)[;}]", src, re.S):
        names|=set(re.findall(r"\b([A-Z][A-Z0-9_]+)\b", m.group(1)))
    names|=set(re.findall(r"public\s+static\s+final\s+String\s+(\w+)", src))
    return names

for p in kt:
    src=open(p,encoding="utf-8").read()
    imported={}
    for m in re.finditer(r"^import\s+(io\.mosip\.vivid\.(?:api|device))\.(\w+)", src, re.M):
        pkg,cls=m.group(1),m.group(2)
        if (pkg,cls) not in api_src: errors.append(f"{p}: imports {pkg}.{cls} which does not exist"); continue
        imported[cls]=api_src[(pkg,cls)]
    code=strip(src,"kt")
    for cls,asrc in imported.items():
        mem=members(asrc)
        # static / nested references: Cls.X
        for m in re.finditer(r"\b"+cls+r"\.(\w+)", code):
            x=m.group(1)
            if x in ("javaClass","Companion"): continue
            if x not in mem: errors.append(f"{p}: {cls}.{x} not found in {cls}.java")
        # nested-type members: Cls.Nested.Y
        for m in re.finditer(r"\b"+cls+r"\.(\w+)\.(\w+)", code):
            nested,y=m.group(1),m.group(2)
            body=re.search(r"(?:enum|class|interface)\s+"+nested+r"\b.*?\{(.*?)\n\s*\}", asrc, re.S)
            if body and y not in members("enum X {"+body.group(1)+"}") and y not in members(body.group(1)):
                errors.append(f"{p}: {cls}.{nested}.{y} not found in {cls}.java")
    # builder/method chains on known api values: we check each `.name(` against the union of
    # all api members when the receiver variable is typed as an api class in this file.
    typed=set(re.findall(r":\s*("+"|".join(map(re.escape,imported))+r")\b", code)) if imported else set()
    if typed:
        union=set()
        for cls in typed: union|=members(imported[cls])
        for var,cls in re.findall(r"\b(\w+)\s*:\s*("+"|".join(map(re.escape,typed))+r")\b", code):
            for m in re.finditer(r"\b"+re.escape(var)+r"\.(\w+)\s*\(", code):
                meth=m.group(1)
                if meth in ("let","also","apply","run","takeIf","toString","hashCode","equals","orElse","map","ifPresent","isPresent","get","clone"): continue
                if meth not in members(imported[cls]): errors.append(f"{p}: {var}.{meth}( -> no '{meth}' in {cls}.java")

# ---- Pigeon generated names -----------------------------------------------------------------
pig=open(os.path.join(MOD,"vivid-android","pigeons","liveness.dart"),encoding="utf-8").read()
declared=set(re.findall(r"(?:class|abstract class)\s+(\w+)", pig))
host_block=pig[pig.find("@HostApi()"):pig.find("@FlutterApi()")]
host_methods=set(re.findall(r"^\s+\w+(?:<[^>]+>)?\??\s+(\w+)\(", host_block, re.M))
for p in kt:
    src=open(p,encoding="utf-8").read()
    for cls in re.findall(r"import io\.mosip\.vivid\.android\.bridge\.gen\.(\w+)", src):
        if cls not in declared: errors.append(f"{p}: imports generated {cls} not declared in pigeons/liveness.dart")
    if "LivenessHostApi" in src and ": LivenessHostApi" in src:
        for m in re.findall(r"override fun (\w+)\([^)]*callback:\s*\(Result<", src):
            if m not in host_methods: errors.append(f"{p}: overrides {m}() which is not a LivenessHostApi method")
        for m in host_methods:
            if f"override fun {m}(" not in src: errors.append(f"{p}: LivenessHostApi.{m}() not implemented")

# ---- ARB consistency --------------------------------------------------------------------------
l10n=os.path.join(MOD,"flutter","lib","liveness","l10n")
keysets={}
for f in sorted(os.listdir(l10n)):
    if not f.endswith(".arb"): continue
    try: d=json.load(open(os.path.join(l10n,f),encoding="utf-8"))
    except Exception as e: errors.append(f"{f}: invalid JSON: {e}"); continue
    keysets[f]={k for k in d if not k.startswith("@")}
if len(set(map(frozenset,keysets.values())))>1:
    base=next(iter(keysets.values()))
    for f,k in keysets.items():
        if k!=base: errors.append(f"{f}: key set differs from others: {sorted(k^base)}")
msgs=open(os.path.join(MOD,"flutter","lib","liveness","liveness_messages.dart"),encoding="utf-8").read()
table=set(re.findall(r"'(liveness_\w+)':\s*\(\)\s*=>", msgs))
if keysets:
    base=next(iter(keysets.values()))
    for k in sorted(base-table): errors.append(f"liveness_messages.dart: ARB key {k} missing from lookup table")
    for k in sorted(table-base): errors.append(f"liveness_messages.dart: lookup table key {k} missing from ARB")
    # every dotted key used in Dart must exist in the ARB
    for p in dart:
        for k in set(re.findall(r"'(liveness\.[a-z_.]+)'", open(p,encoding="utf-8").read())):
            if k.replace(".","_") not in base: errors.append(f"{p}: uses key {k} missing from ARB")
    # every key produced by the mapping functions must exist
    hints=["NONE","MOVE_CLOSER","MOVE_BACK","CENTER_FACE","LOOK_AT_CAMERA","IMPROVE_LIGHTING","HOLD_STILL","ONLY_ONE_FACE","CHECKING_LIVENESS","PLEASE_CONTINUE","ACTION_DETECTED","CAPTURED_OK","TRY_AGAIN","RETRY_AVAILABLE","MAX_RETRIES_REACHED","DEVICE_RECONNECTING","DEVICE_DISCONNECTED"]
    api_hints=set(re.findall(r"\b([A-Z][A-Z_]+)\b", re.search(r"enum Hint \{(.*?)\}", api_src[("io.mosip.vivid.api","SessionState")], re.S).group(1)))
    for h in api_hints-set(hints): errors.append(f"liveness_messages.dart: Hint.{h} not mapped")
    for ct in re.findall(r"\b([A-Z_]+)\(", re.search(r"enum ChallengeType \{(.*?);", api_src[("io.mosip.vivid.api","ChallengeType")], re.S).group(1)):
        if f"liveness_challenge_{ct.lower()}" not in base: errors.append(f"ARB: missing liveness_challenge_{ct.lower()}")
    for ph in re.findall(r"\b([A-Z_]+)\b", re.search(r"enum Phase \{(.*?)\}", api_src[("io.mosip.vivid.api","SessionState")], re.S).group(1)):
        if f"liveness_phase_{ph.lower()}" not in base: errors.append(f"ARB: missing liveness_phase_{ph.lower()}")

print(f"checked {len(kt)} Kotlin files, {len(dart)} Dart files, {len(keysets)} ARB files")
for w in warnings: print("WARN", w)
for e in errors: print("ERROR", e)
sys.exit(1 if errors else 0)
PY
