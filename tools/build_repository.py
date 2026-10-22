#!/usr/bin/env python3
"""
Gera o repository.json (índice do Hubitat Package Manager) a partir dos
packageManifest.json de cada pasta em drivers/ e apps/.

Cada manifest carrega um bloco extra "x-catalog" (ignorado pelo HPM) com:
  - theme:       tema usado para agrupar/ordenar o índice e o README
  - order:       posição dentro do tema
  - description: descrição curta exibida no HPM
  - packageId:   id estável do pacote no repository.json (UUID, nunca mudar)

Uso:  python3 tools/build_repository.py          # regenera repository.json
      python3 tools/build_repository.py --check  # falha se estiver desatualizado (CI)
"""
import json
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
RAW = "https://raw.githubusercontent.com/rngarcia/hubitat/main"
PAYPAL = "https://www.paypal.com/donate/?business=rngarcia%40gmail.com&currency_code=USD"

# Ordem dos temas no índice
THEMES = [
    "Energia e solar",
    "Água e aquecimento",
    "Climatização",
    "Irrigação",
    "Iluminação e tomadas",
    "Sensores",
    "Eletrodomésticos",
    "Integrações cloud",
    "Outros",
]


def load_manifests():
    items = []
    for kind in ("drivers", "apps"):
        for mf in sorted((ROOT / kind).glob("*/packageManifest.json")):
            data = json.loads(mf.read_text(encoding="utf-8"))
            cat = data.get("x-catalog", {})
            theme = cat.get("theme", "Outros")
            if theme not in THEMES:
                sys.exit(f"{mf}: tema desconhecido '{theme}' (adicione em THEMES)")
            for key in ("packageId", "description"):
                if key not in cat:
                    sys.exit(f"{mf}: x-catalog.{key} ausente")
            items.append((THEMES.index(theme), cat.get("order", 99), data["packageName"], mf, data, cat))
    items.sort(key=lambda i: i[:3])
    return items


def build():
    packages = []
    for _, _, _, mf, data, cat in load_manifests():
        rel = mf.relative_to(ROOT).as_posix()
        packages.append({
            "id": cat["packageId"],
            "name": data["packageName"],
            "category": data.get("category", "Integrations"),
            "location": f"{RAW}/{rel}",
            "description": cat["description"],
            "tags": data.get("tags", []),
        })
    repo = {
        "author": "Ricardo Nogueira Garcia",
        "gitHubUrl": "https://github.com/rngarcia",
        "payPalUrl": PAYPAL,
        "packages": packages,
    }
    return json.dumps(repo, indent=2, ensure_ascii=False) + "\n"


if __name__ == "__main__":
    out = ROOT / "repository.json"
    text = build()
    if "--check" in sys.argv:
        if not out.exists() or out.read_text(encoding="utf-8") != text:
            sys.exit("repository.json desatualizado: rode python3 tools/build_repository.py")
        print("repository.json ok")
    else:
        out.write_text(text, encoding="utf-8")
        n = len(json.loads(text)["packages"])
        print(f"repository.json gerado com {n} pacotes")
