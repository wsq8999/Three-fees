from __future__ import annotations

"""从版本化Markdown文件加载节点提示词，避免长提示词散落在Python代码中。"""

from functools import lru_cache
from pathlib import Path

PROMPT_ROOT = Path(__file__).resolve().parent / "prompts"


@lru_cache
def load_prompt(filename: str) -> str:
    """只允许读取提示词目录下的直接文件，并缓存不可变内容。"""
    path = (PROMPT_ROOT / filename).resolve()
    if path.parent != PROMPT_ROOT.resolve() or path.suffix != ".md":
        raise ValueError("提示词文件路径无效")
    return path.read_text(encoding="utf-8").strip()
