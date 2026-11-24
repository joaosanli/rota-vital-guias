"""Gera imagens PNG (estilo terminal) a partir dos logs .txt de evidencias/.

Os .txt são a saída real dos comandos (capturada com `tee`); as imagens só
facilitam a visualização. Uso: python scripts/gerar-prints.py
"""
import re
import textwrap
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ANSI = re.compile(r"\x1b\[[0-9;]*m")
PASTA = Path(__file__).resolve().parent.parent / "evidencias"
SAIDA = PASTA / "prints"
FONTE = ImageFont.truetype("C:/Windows/Fonts/consola.ttf", 15)
LARGURA_COL, MAX_LINHAS = 130, 70


def cor(linha):
    if linha.startswith("$"):
        return (120, 220, 120)
    if re.search(r"error|timed out|HTTP [45]\d\d", linha, re.I):
        return (255, 120, 110)
    if "HTTP 2" in linha or "invocacaoNesteAmbiente" in linha or "Succeeded" in linha:
        return (130, 190, 255)
    return (220, 220, 220)


def renderizar(txt: Path):
    linhas = []
    for bruta in ANSI.sub("", txt.read_text(encoding="utf-8", errors="replace")).splitlines():
        linhas += textwrap.wrap(bruta, LARGURA_COL) or [""]
    if len(linhas) > MAX_LINHAS:
        linhas = linhas[:MAX_LINHAS] + [f"... ({len(linhas) - MAX_LINHAS} linhas omitidas, ver {txt.name})"]
    titulo = f"evidencias/{txt.name}"
    alt_linha = 19
    img = Image.new("RGB", (LARGURA_COL * 8 + 40, (len(linhas) + 2) * alt_linha + 30), (30, 30, 30))
    d = ImageDraw.Draw(img)
    d.rectangle([0, 0, img.width, 28], fill=(55, 55, 55))
    d.text((12, 6), titulo, font=FONTE, fill=(200, 200, 200))
    for i, linha in enumerate(linhas):
        d.text((16, 40 + i * alt_linha), linha, font=FONTE, fill=cor(linha))
    SAIDA.mkdir(exist_ok=True)
    img.save(SAIDA / (txt.stem + ".png"))


for arquivo in sorted(PASTA.glob("*.txt")):
    renderizar(arquivo)
    print("gerado:", (SAIDA / (arquivo.stem + ".png")).relative_to(PASTA.parent))
