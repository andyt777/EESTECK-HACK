"""Small RAG retriever: BM25 over docs/*.md and config/*.yaml, chunked by Markdown headings."""

from __future__ import annotations

import math
import re
import unicodedata
from collections import Counter
from dataclasses import dataclass
from pathlib import Path

MAX_CHUNK = 1500
STOPWORDS = {
    "si", "sau", "de", "la", "in", "din", "cu", "pe", "pentru", "care", "ce", "este", "sunt", "un", "o", "a", "al",
    "ale", "lui", "nu", "se", "mai", "cum", "cand", "the", "and", "of", "to", "is", "ca", "fi", "sa", "ii",
}


def normalise(text: str) -> str:
    text = unicodedata.normalize("NFKD", text.lower())
    return "".join(ch for ch in text if not unicodedata.combining(ch))


def tokenize(text: str) -> list[str]:
    return [t for t in re.findall(r"[a-z0-9_]+", normalise(text)) if len(t) > 1 and t not in STOPWORDS]


@dataclass
class Chunk:
    source: str
    heading: str
    text: str
    tokens: Counter
    length: int


class DocsIndex:
    def __init__(self, chunks: list[Chunk]):
        self.chunks = chunks
        self.avg_len = sum(c.length for c in chunks) / len(chunks) if chunks else 1.0
        df: Counter = Counter()
        for c in chunks:
            df.update(set(c.tokens))
        n = len(chunks)
        self.idf = {t: math.log(1 + (n - f + 0.5) / (f + 0.5)) for t, f in df.items()}

    @staticmethod
    def build(dirs, root_names: dict[Path, str] | None = None) -> DocsIndex:
        chunks: list[Chunk] = []
        for directory in dirs:
            directory = Path(directory)
            if not directory.exists():
                continue
            prefix = (root_names or {}).get(directory, directory.name)
            for path in sorted([*directory.glob("*.md"), *directory.glob("*.yaml"), *directory.glob("*.yml")]):
                try:
                    content = path.read_text(encoding="utf-8")
                except (OSError, UnicodeDecodeError):
                    continue
                chunks.extend(split(f"{prefix}/{path.name}", content))
        return DocsIndex(chunks)

    def search(self, query: str, k: int = 4) -> list[dict]:
        terms = tokenize(query)
        if not terms or not self.chunks:
            return []
        scored = []
        k1, b = 1.5, 0.75
        for chunk in self.chunks:
            score = 0.0
            for t in terms:
                f = chunk.tokens.get(t)
                if f:
                    score += self.idf.get(t, 0) * f * (k1 + 1) / (f + k1 * (1 - b + b * chunk.length / self.avg_len))
            if score > 0:
                scored.append((score, chunk))
        scored.sort(key=lambda x: -x[0])
        return [{"source": c.source, "heading": c.heading, "score": round(s, 2), "text": c.text[:900]}
                for s, c in scored[:k]]


def split(source: str, content: str) -> list[Chunk]:
    chunks, heading, buf = [], source, []

    def flush():
        text = "\n".join(buf).strip()
        while text:
            part, text = text[:MAX_CHUNK], text[MAX_CHUNK:]
            tokens = tokenize(heading + " " + part)
            if tokens:
                chunks.append(Chunk(source, heading, part, Counter(tokens), len(tokens)))

    for line in content.splitlines():
        if source.endswith(".md") and re.match(r"^#{1,4} ", line):
            flush()
            heading, buf = line.lstrip("#").strip(), []
        else:
            buf.append(line)
    flush()
    return chunks
