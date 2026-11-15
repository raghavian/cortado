#!/usr/bin/env python3
"""
Renders PRIVACY.md to <out>/privacy.html for GitHub Pages (the Play Store needs a public URL).

Only the Markdown that PRIVACY.md uses is supported: headings, paragraphs, bullet lists,
tables, **bold**, _italic_ and [links](url). Standard library only.
"""
import argparse
import html
import re

STYLE = """
body{font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif;max-width:46rem;margin:2rem auto;
padding:0 1rem;line-height:1.55;color:#2E1A10;background:#FFFBF7}
h1{color:#5B3A29}h2{color:#5B3A29;margin-top:2rem}a{color:#3E7D5A}
table{border-collapse:collapse;width:100%;font-size:.92rem}th,td{border:1px solid #E5D6C6;padding:.45rem;
text-align:left;vertical-align:top}th{background:#F6EBDD}
"""


def inline(text: str) -> str:
    t = html.escape(text, quote=False)
    t = re.sub(r"\[([^\]]+)\]\(([^)]+)\)", r'<a href="\2">\1</a>', t)
    t = re.sub(r"\*\*(.+?)\*\*", r"<strong>\1</strong>", t)
    t = re.sub(r"(?<![\w])_(.+?)_(?![\w])", r"<em>\1</em>", t)
    return t


def render(md: str) -> str:
    out, para, lines = [], [], md.splitlines()
    i = 0

    def flush():
        if para:
            out.append(f"<p>{inline(' '.join(para))}</p>")
            para.clear()

    while i < len(lines):
        line = lines[i].rstrip()
        if not line:
            flush()
        elif line.startswith("#"):
            flush()
            level = len(line) - len(line.lstrip("#"))
            out.append(f"<h{level}>{inline(line[level:].strip())}</h{level}>")
        elif line.startswith("- "):
            flush()
            out.append("<ul>")
            while i < len(lines) and lines[i].startswith("- "):
                out.append(f"<li>{inline(lines[i][2:].strip())}</li>")
                i += 1
            out.append("</ul>")
            continue
        elif line.startswith("|"):
            flush()
            rows = []
            while i < len(lines) and lines[i].startswith("|"):
                cells = [c.strip() for c in lines[i].strip().strip("|").split("|")]
                if not all(re.fullmatch(r":?-{3,}:?", c) for c in cells):
                    rows.append(cells)
                i += 1
            out.append("<table>")
            out.append("<tr>" + "".join(f"<th>{inline(c)}</th>" for c in rows[0]) + "</tr>")
            for r in rows[1:]:
                out.append("<tr>" + "".join(f"<td>{inline(c)}</td>" for c in r) + "</tr>")
            out.append("</table>")
            continue
        else:
            para.append(line)
        i += 1
    flush()
    return "\n".join(out)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", default="PRIVACY.md")
    ap.add_argument("--out", default="site")
    args = ap.parse_args()
    with open(args.src, encoding="utf-8") as f:
        md = f.read()
    if "{{" in md:
        print("warning: PRIVACY.md still has {{placeholders}}")
    body = render(md)
    page = (
        '<!doctype html><html lang="en"><meta charset="utf-8">'
        '<meta name="viewport" content="width=device-width,initial-scale=1">'
        f"<title>babylatte – Privacy Policy</title><style>{STYLE}</style><body>{body}</body></html>"
    )
    with open(f"{args.out}/privacy.html", "w", encoding="utf-8") as f:
        f.write(page)


if __name__ == "__main__":
    main()
