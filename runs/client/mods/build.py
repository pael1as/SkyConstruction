import json as js
import re
import zipfile as zf
import tomllib
import tomli_w
from concurrent import futures
from functools import partial
from pathlib import Path

WORLDGEN_RE = re.compile(
    r'^data/[^/]+/(?:worldgen/structure|worldgen/placed_feature)/[^/]+\.json$'
)
METAINF_PATH = "META-INF/neoforge.mods.toml"
MOD_NAME = "FixStructuresForAeroIslands"


def parse_mod(path_to_mod: Path, main_json: dict, main_path: Path) -> str | None:
    try:
        with zf.ZipFile(path_to_mod) as zip_file:
            names = zip_file.namelist()
            json_paths = [n for n in names if WORLDGEN_RE.match(n)]

            if METAINF_PATH not in names:
                raise Exception("no META-INF/neoforge.mods.toml")

            toml_data = tomllib.loads(zip_file.read(METAINF_PATH).decode("utf-8"))
            mods = toml_data.get("mods")
            if not isinstance(mods, list) or not mods or "modId" not in mods[0]:
                raise Exception("no [[mods]] section with modId")
            mod_id = mods[0]["modId"]

            out_dir = main_path / MOD_NAME
            for json_path in json_paths:
                with zip_file.open(json_path) as fp:
                    data = js.load(fp)
                out = out_dir / json_path
                out.parent.mkdir(parents=True, exist_ok=True)
                out.write_text(
                    js.dumps(main_json | {"delegate": data}, ensure_ascii=False),
                    encoding="utf-8",
                )

        return mod_id
    except Exception as e:
        print(f"[FAIL] {path_to_mod.name}: {e}")
        return None


if __name__ == "__main__":
    root = Path(__file__).resolve().parent
    main_json = js.loads((root / "wrapper.json").read_text(encoding="utf-8"))

    paths_mods = root / "mods"
    paths_mods.mkdir(exist_ok=True)
    mods = [p for p in (root / "mods").iterdir()
            if p.is_file() and p.suffix.lower() == ".jar"]

    meta_dir = root / MOD_NAME / "META-INF"
    meta_dir.mkdir(parents=True, exist_ok=True)
    main_toml = meta_dir / "neoforge.mods.toml"

    with futures.ThreadPoolExecutor() as pool:
        results = list(pool.map(
            partial(parse_mod, main_json=main_json, main_path=root), mods
        ))

    ok_ids = list(dict.fromkeys(r for r in results if r is not None))
    failed = [p.name for p, r in zip(mods, results) if r is None]
    print(f"OK: {len(mods) - len(failed)}/{len(mods)}", failed)

    toml_data = {
        "modLoader": "javafml",
        "loaderVersion": "[4,)",
        "license": "All Rights Reserved",
        "mods": [{
            "modId": MOD_NAME,
            "version": "1.0.0",
            "displayName": MOD_NAME,
            "description": "Structure generation fixes for various mods",
        }],
        "dependencies": {
            MOD_NAME: [
                {
                    "modId": mid,
                    "type": "required",
                    "versionRange": "[0,)",
                    "ordering": "AFTER",
                    "side": "BOTH",
                }
                for mid in ok_ids
            ]
        },
    }
    main_toml.write_bytes(tomli_w.dumps(toml_data).encode("utf-8"))