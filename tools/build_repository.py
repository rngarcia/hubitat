#!/usr/bin/env python3
"""
Gera o repository.json (índice do Hubitat Package Manager) e as tabelas do README a partir dos
packageManifest.json de cada pasta em drivers/ e apps/.

Cada manifest carrega um bloco extra "x-catalog" (ignorado pelo HPM) com:
  - theme:       tema usado para agrupar/ordenar o índice e o README
  - order:       posição dentro do tema
  - description: descrição curta exibida no HPM
  - packageId:   id estável do pacote no repository.json (UUID, nunca mudar)
  - publish:     false para manter a pasta fora do índice HPM

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
    "Segurança",
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
            if cat.get("publish") is False:
                continue  # fora do índice HPM (ex.: depende de app ainda não publicado)
            theme = cat.get("theme", "Outros")
            if theme not in THEMES:
                sys.exit(f"{mf}: tema desconhecido '{theme}' (adicione em THEMES)")
            for key in ("packageId", "description"):
                if key not in cat:
                    sys.exit(f"{mf}: x-catalog.{key} ausente")
            items.append((THEMES.index(theme), cat.get("order", 99), data["packageName"], mf, data, cat))
    items.sort(key=lambda i: i[:3])
    return items


def build_readme_tables(items):
    """Tabelas por tema para o README (entre os marcadores PACKAGES)."""
    out, current = [], None
    for _, _, _, mf, data, cat in items:
        theme = cat.get("theme", "Outros")
        if theme != current:
            current = theme
            out += ["", f"### {theme}", "| Package | Version | Drivers | Manifest |", "|---|---|---|---|"]
        folder = mf.parent.relative_to(ROOT).as_posix()
        rel = mf.relative_to(ROOT).as_posix()
        n = len(data.get("drivers", [])) + len(data.get("apps", []))
        out.append(f"| [{data['packageName']}]({folder}) | {data['version']} | {n} | [packageManifest.json]({RAW}/{rel}) |")
    hidden = []
    for kind in ("drivers", "apps"):
        for mf in sorted((ROOT / kind).glob("*/packageManifest.json")):
            data = json.loads(mf.read_text(encoding="utf-8"))
            if data.get("x-catalog", {}).get("publish") is False:
                hidden.append(f"- [{data['packageName']}]({mf.parent.relative_to(ROOT).as_posix()}): not in HPM yet")
    if hidden:
        out += ["", "### Not in HPM yet", *hidden]
    return "\n".join(out).lstrip("\n") + "\n"


def render_readme(items):
    readme = ROOT / "README.md"
    text = readme.read_text(encoding="utf-8")
    start, end = "<!-- PACKAGES:START -->", "<!-- PACKAGES:END -->"
    a, b = text.index(start) + len(start), text.index(end)
    return text[:a] + "\n" + build_readme_tables(items) + text[b:]


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
    readme = render_readme(load_manifests())
    if "--check" in sys.argv:
        if not out.exists() or out.read_text(encoding="utf-8") != text:
            sys.exit("repository.json desatualizado: rode python3 tools/build_repository.py")
        if (ROOT / "README.md").read_text(encoding="utf-8") != readme:
            sys.exit("tabelas do README desatualizadas: rode python3 tools/build_repository.py")
        print("repository.json e README ok")
    else:
        out.write_text(text, encoding="utf-8")
        (ROOT / "README.md").write_text(readme, encoding="utf-8")
        n = len(json.loads(text)["packages"])
        print(f"repository.json gerado com {n} pacotes")
