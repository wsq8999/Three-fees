"""从FastAPI应用导出唯一可信的OpenAPI契约。"""

import json
import sys
from importlib import import_module
from pathlib import Path

# 把后端源码加入导入路径，使脚本可从仓库根目录或scripts目录执行。
root = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(root / "backend"))

app = import_module("app.main").app
target = root / "contracts" / "openapi.json"
target.parent.mkdir(parents=True, exist_ok=True)
target.write_text(
    json.dumps(app.openapi(), ensure_ascii=False, indent=2) + "\n",
    encoding="utf-8",
)
print(target)
